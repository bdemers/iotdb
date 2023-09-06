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

import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.load.LoadTsFilePieceNode;
import org.apache.iotdb.db.queryengine.plan.scheduler.load.LoadTsFileScheduler;
import org.apache.iotdb.tsfile.utils.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * TsFileDataManager batches splits generated by TsFileSplitter as LoadTsFilePieceNode, route the
 * splits to associated replica set, and sends them to the replicas with the provided dispatching
 * function.
 */
@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class TsFileDataManager {

  private static final Logger logger = LoggerFactory.getLogger(TsFileDataManager.class);
  private final DispatchFunction dispatchFunction;
  private final DataPartitionBatchFetcher partitionBatchFetcher;
  private final PlanNodeId planNodeId;
  private final File targetFile;

  private long dataSize;
  private final Map<TRegionReplicaSet, LoadTsFilePieceNode> replicaSet2Piece;
  private final List<ChunkData> nonDirectionalChunkData;

  @FunctionalInterface
  public interface DispatchFunction {

    boolean dispatchOnePieceNode(LoadTsFilePieceNode pieceNode, TRegionReplicaSet replicaSet);
  }

  public TsFileDataManager(
      DispatchFunction dispatchFunction,
      PlanNodeId planNodeId,
      File targetFile,
      DataPartitionBatchFetcher partitionBatchFetcher) {
    this.dispatchFunction = dispatchFunction;
    this.planNodeId = planNodeId;
    this.targetFile = targetFile;
    this.dataSize = 0;
    this.replicaSet2Piece = new HashMap<>();
    this.nonDirectionalChunkData = new ArrayList<>();
    this.partitionBatchFetcher = partitionBatchFetcher;
  }

  public boolean addOrSendTsFileData(TsFileData tsFileData) {
    return tsFileData.isModification()
        ? addOrSendDeletionData(tsFileData)
        : addOrSendChunkData((ChunkData) tsFileData);
  }

  private boolean addOrSendChunkData(ChunkData chunkData) {
    nonDirectionalChunkData.add(chunkData);
    dataSize += chunkData.getDataSize();

    if (dataSize > LoadTsFileScheduler.MAX_MEMORY_SIZE) {
      routeChunkData();

      // start to dispatch from the biggest TsFilePieceNode
      List<TRegionReplicaSet> sortedReplicaSets =
          replicaSet2Piece.keySet().stream()
              .sorted(
                  Comparator.comparingLong(o -> replicaSet2Piece.get(o).getDataSize()).reversed())
              .collect(Collectors.toList());

      for (TRegionReplicaSet sortedReplicaSet : sortedReplicaSets) {
        LoadTsFilePieceNode pieceNode = replicaSet2Piece.get(sortedReplicaSet);
        if (pieceNode.getDataSize() == 0) { // total data size has been reduced to 0
          break;
        }
        if (!dispatchFunction.dispatchOnePieceNode(pieceNode, sortedReplicaSet)) {
          return false;
        }

        dataSize -= pieceNode.getDataSize();
        replicaSet2Piece.put(
            sortedReplicaSet,
            new LoadTsFilePieceNode(
                planNodeId, targetFile)); // can not just remove, because of deletion
        if (dataSize <= LoadTsFileScheduler.MAX_MEMORY_SIZE) {
          break;
        }
      }
    }

    return true;
  }

  private void routeChunkData() {
    if (nonDirectionalChunkData.isEmpty()) {
      return;
    }

    List<TRegionReplicaSet> replicaSets =
        partitionBatchFetcher.queryDataPartition(
            nonDirectionalChunkData.stream()
                .map(data -> new Pair<>(data.getDevice(), data.getTimePartitionSlot()))
                .collect(Collectors.toList()));
    IntStream.range(0, nonDirectionalChunkData.size())
        .forEach(
            i ->
                replicaSet2Piece
                    .computeIfAbsent(
                        replicaSets.get(i), o -> new LoadTsFilePieceNode(planNodeId, targetFile))
                    .addTsFileData(nonDirectionalChunkData.get(i)));
    nonDirectionalChunkData.clear();
  }

  private boolean addOrSendDeletionData(TsFileData deletionData) {
    routeChunkData(); // ensure chunk data will be added before deletion

    for (Map.Entry<TRegionReplicaSet, LoadTsFilePieceNode> entry : replicaSet2Piece.entrySet()) {
      dataSize += deletionData.getDataSize();
      entry.getValue().addTsFileData(deletionData);
    }
    return true;
  }

  public boolean sendAllTsFileData() {
    routeChunkData();

    for (Map.Entry<TRegionReplicaSet, LoadTsFilePieceNode> entry : replicaSet2Piece.entrySet()) {
      if (!dispatchFunction.dispatchOnePieceNode(entry.getValue(), entry.getKey())) {
        logger.warn("Dispatch piece node {} of TsFile {} error.", entry.getValue(), targetFile);
        return false;
      }
    }
    return true;
  }
}
