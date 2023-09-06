/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.execution.load;

import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.exception.ClientManagerException;
import org.apache.iotdb.commons.client.sync.SyncDataNodeInternalServiceClient;
import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.concurrent.IoTThreadFactory;
import org.apache.iotdb.db.exception.mpp.FragmentInstanceDispatchException;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.load.LoadTsFileNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.load.LoadTsFilePieceNode;
import org.apache.iotdb.db.queryengine.plan.scheduler.load.LoadTsFileScheduler.LoadCommand;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResource;
import org.apache.iotdb.mpp.rpc.thrift.TLoadCommandReq;
import org.apache.iotdb.mpp.rpc.thrift.TLoadResp;
import org.apache.iotdb.mpp.rpc.thrift.TTsFilePieceReq;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.tsfile.utils.Pair;

import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.iotdb.db.queryengine.plan.scheduler.load.LoadTsFileDispatcherImpl.NODE_CONNECTION_ERROR;

public class TsFileSplitSender {

  private static final int MAX_RETRY = 5;
  private static final long RETRY_INTERVAL_MS = 6_000L;
  private static final Logger logger = LoggerFactory.getLogger(TsFileSplitSender.class);

  private LoadTsFileNode loadTsFileNode;
  private DataPartitionBatchFetcher targetPartitionFetcher;
  private long targetPartitionInterval;
  private final IClientManager<TEndPoint, SyncDataNodeInternalServiceClient>
      internalServiceClientManager;
  // All consensus groups accessed in Phase1 should be notified in Phase2
  private final Set<TRegionReplicaSet> allReplicaSets = new ConcurrentSkipListSet<>();
  private String uuid;
  private Map<TDataNodeLocation, Double> dataNodeThroughputMap = new ConcurrentHashMap<>();
  private Random random = new Random();
  private boolean isGeneratedByPipe;
  private Map<Pair<LoadTsFilePieceNode, TRegionReplicaSet>, Exception> phaseOneFailures =
      new ConcurrentHashMap<>();
  private Map<TRegionReplicaSet, Exception> phaseTwoFailures = new HashMap<>();

  public TsFileSplitSender(
      LoadTsFileNode loadTsFileNode,
      DataPartitionBatchFetcher targetPartitionFetcher,
      long targetPartitionInterval,
      IClientManager<TEndPoint, SyncDataNodeInternalServiceClient> internalServiceClientManager,
      boolean isGeneratedByPipe) {
    this.loadTsFileNode = loadTsFileNode;
    this.targetPartitionFetcher = targetPartitionFetcher;
    this.targetPartitionInterval = targetPartitionInterval;
    this.internalServiceClientManager = internalServiceClientManager;
    this.isGeneratedByPipe = isGeneratedByPipe;
  }

  public void start() throws IOException {
    // skip files without data
    loadTsFileNode.getResources().removeIf(f -> f.getDevices().isEmpty());
    uuid = UUID.randomUUID().toString();

    boolean isFirstPhaseSuccess = firstPhase(loadTsFileNode);
    boolean isSecondPhaseSuccess = secondPhase(isFirstPhaseSuccess);
    if (isFirstPhaseSuccess && isSecondPhaseSuccess) {
      logger.info("Load TsFiles {} Successfully", loadTsFileNode.getResources());
    } else {
      logger.warn("Can not Load TsFiles {}", loadTsFileNode.getResources());
    }
  }

  private boolean firstPhase(LoadTsFileNode node) throws IOException {
    TsFileDataManager tsFileDataManager =
        new TsFileDataManager(
            this::dispatchOnePieceNode,
            node.getPlanNodeId(),
            node.lastResource().getTsFile(),
            targetPartitionFetcher);
    ExecutorService executorService =
        IoTDBThreadPoolFactory.newThreadPool(
            16,
            Integer.MAX_VALUE,
            20,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new IoTThreadFactory("MergedTsFileSplitter"),
            "MergedTsFileSplitter");
    new MergedTsFileSplitter(
            node.getResources().stream()
                .map(TsFileResource::getTsFile)
                .collect(Collectors.toList()),
            tsFileDataManager::addOrSendTsFileData,
            executorService,
            targetPartitionInterval)
        .splitTsFileByDataPartition();
    return tsFileDataManager.sendAllTsFileData() && phaseOneFailures.isEmpty();
  }

  private boolean secondPhase(boolean isFirstPhaseSuccess) {
    logger.info("Start dispatching Load command for uuid {}", uuid);
    TLoadCommandReq loadCommandReq =
        new TLoadCommandReq(
            (isFirstPhaseSuccess ? LoadCommand.EXECUTE : LoadCommand.ROLLBACK).ordinal(), uuid);
    loadCommandReq.setIsGeneratedByPipe(isGeneratedByPipe);

    for (TRegionReplicaSet replicaSet : allReplicaSets) {
      loadCommandReq.setUseConsensus(true);
      for (TDataNodeLocation dataNodeLocation : replicaSet.getDataNodeLocations()) {
        TEndPoint endPoint = dataNodeLocation.getInternalEndPoint();
        Exception groupException = null;
        loadCommandReq.setConsensusGroupId(replicaSet.getRegionId());

        for (int i = 0; i < MAX_RETRY; i++) {
          try (SyncDataNodeInternalServiceClient client =
              internalServiceClientManager.borrowClient(endPoint)) {
            TLoadResp loadResp = client.sendLoadCommand(loadCommandReq);
            if (!loadResp.isAccepted()) {
              logger.warn(loadResp.message);
              groupException = new FragmentInstanceDispatchException(loadResp.status);
            }
            break;
          } catch (ClientManagerException | TException e) {
            logger.warn(NODE_CONNECTION_ERROR, endPoint, e);
            TSStatus status = new TSStatus();
            status.setCode(TSStatusCode.DISPATCH_ERROR.getStatusCode());
            status.setMessage(
                "can't connect to node {}, please reset longer dn_connection_timeout_ms "
                    + "in iotdb-common.properties and restart iotdb."
                    + endPoint);
            groupException = new FragmentInstanceDispatchException(status);
          }
          try {
            Thread.sleep(RETRY_INTERVAL_MS);
          } catch (InterruptedException e) {
            groupException = e;
            break;
          }
        }

        if (groupException != null) {
          phaseTwoFailures.put(replicaSet, groupException);
        } else {
          break;
        }
      }
    }

    return phaseTwoFailures.isEmpty();
  }

  /**
   * The rank (probability of being chosen) is calculated as throughput / totalThroughput for those
   * nodes that have not been used, their throughput is defined as Float.MAX_VALUE
   *
   * @param replicaSet replica set to be ranked
   * @return the nodes and their ranks
   */
  private List<Pair<TDataNodeLocation, Double>> rankLocations(TRegionReplicaSet replicaSet) {
    List<Pair<TDataNodeLocation, Double>> locations =
        new ArrayList<>(replicaSet.dataNodeLocations.size());
    // retrieve throughput of each node
    double totalThroughput = 0.0;
    for (TDataNodeLocation dataNodeLocation : replicaSet.getDataNodeLocations()) {
      // use Float.MAX_VALUE so that they can be added together
      double throughput =
          dataNodeThroughputMap.computeIfAbsent(dataNodeLocation, l -> (double) Float.MAX_VALUE);
      locations.add(new Pair<>(dataNodeLocation, throughput));
      totalThroughput += throughput;
    }
    // calculate cumulative ranks
    locations.get(0).right = locations.get(0).right / totalThroughput;
    for (int i = 1; i < locations.size(); i++) {
      Pair<TDataNodeLocation, Double> location = locations.get(i);
      location.right = location.right / totalThroughput + locations.get(i - 1).right;
    }
    return locations;
  }

  private Pair<TDataNodeLocation, Double> chooseNextLocation(
      List<Pair<TDataNodeLocation, Double>> locations) {
    int chosen = 0;
    double dice = random.nextDouble();
    for (int i = 1; i < locations.size() - 1; i++) {
      if (locations.get(i - 1).right <= dice && dice < locations.get(i).right) {
        chosen = i;
      }
    }
    Pair<TDataNodeLocation, Double> chosenPair = locations.remove(chosen);
    // update ranks
    for (Pair<TDataNodeLocation, Double> location : locations) {
      location.right = location.right / (1 - chosenPair.right);
    }
    return chosenPair;
  }

  @SuppressWarnings("BusyWait")
  public boolean dispatchOnePieceNode(LoadTsFilePieceNode pieceNode, TRegionReplicaSet replicaSet) {
    allReplicaSets.add(replicaSet);

    TTsFilePieceReq loadTsFileReq =
        new TTsFilePieceReq(pieceNode.serializeToByteBuffer(), uuid, replicaSet.getRegionId());
    loadTsFileReq.isRelay = true;
    List<Pair<TDataNodeLocation, Double>> locations = rankLocations(replicaSet);

    long startTime = 0;
    boolean loadSucceed = false;
    Exception lastConnectionError = null;
    TDataNodeLocation currLocation = null;
    while (!locations.isEmpty()) {
      // the chosen location is removed from the list
      Pair<TDataNodeLocation, Double> locationRankPair = chooseNextLocation(locations);
      currLocation = locationRankPair.left;
      startTime = System.currentTimeMillis();
      for (int i = 0; i < MAX_RETRY; i++) {
        try (SyncDataNodeInternalServiceClient client =
            internalServiceClientManager.borrowClient(currLocation.internalEndPoint)) {
          TLoadResp loadResp = client.sendTsFilePieceNode(loadTsFileReq);
          if (!loadResp.isAccepted()) {
            logger.warn(loadResp.message);
            phaseOneFailures.put(
                new Pair<>(pieceNode, replicaSet),
                new FragmentInstanceDispatchException(loadResp.status));
            return false;
          }
          loadSucceed = true;
          break;
        } catch (ClientManagerException | TException e) {
          lastConnectionError = e;
        }

        try {
          Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
          return false;
        }
      }

      if (loadSucceed) {
        break;
      }
    }

    if (!loadSucceed) {
      String warning = NODE_CONNECTION_ERROR;
      logger.warn(warning, locations, lastConnectionError);
      TSStatus status = new TSStatus();
      status.setCode(TSStatusCode.DISPATCH_ERROR.getStatusCode());
      status.setMessage(warning + locations);
      phaseOneFailures.put(
          new Pair<>(pieceNode, replicaSet), new FragmentInstanceDispatchException(status));
      return false;
    }
    long timeConsumption = System.currentTimeMillis() - startTime;
    dataNodeThroughputMap.put(currLocation, pieceNode.getDataSize() * 1.0 / timeConsumption);
    return true;
  }
}
