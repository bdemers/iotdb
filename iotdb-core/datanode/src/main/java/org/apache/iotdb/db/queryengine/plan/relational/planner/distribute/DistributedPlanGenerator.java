/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.iotdb.db.queryengine.plan.relational.planner.distribute;

import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.common.rpc.thrift.TSeriesPartitionSlot;
import org.apache.iotdb.commons.partition.SchemaPartition;
import org.apache.iotdb.commons.schema.table.column.TsTableColumnCategory;
import org.apache.iotdb.commons.utils.PathUtils;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.common.QueryId;
import org.apache.iotdb.db.queryengine.plan.planner.distribution.NodeDistribution;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanVisitor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.WritePlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.TableDeviceFetchNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.TableDeviceQueryNode;
import org.apache.iotdb.db.queryengine.plan.relational.analyzer.Analysis;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.DeviceEntry;
import org.apache.iotdb.db.queryengine.plan.relational.planner.OrderingScheme;
import org.apache.iotdb.db.queryengine.plan.relational.planner.SortOrder;
import org.apache.iotdb.db.queryengine.plan.relational.planner.Symbol;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.CollectNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.FilterNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.LimitNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.MergeSortNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OffsetNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.OutputNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.ProjectNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.SortNode;
import org.apache.iotdb.db.queryengine.plan.relational.planner.node.TableScanNode;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Expression;
import org.apache.iotdb.db.queryengine.plan.statement.component.Ordering;

import org.apache.tsfile.file.metadata.IDeviceID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.TableDeviceSchemaValidator.parseDeviceIdArray;
import static org.apache.iotdb.db.queryengine.plan.relational.planner.optimizations.PushPredicateIntoTableScan.containsDiffFunction;
import static org.apache.iotdb.db.utils.constant.TestConstant.TIMESTAMP_STR;

/** This class is used to generate distributed plan for table model. */
public class DistributedPlanGenerator
    extends PlanVisitor<List<PlanNode>, DistributedPlanGenerator.PlanContext> {
  private final QueryId queryId;
  private final Analysis analysis;
  Map<PlanNodeId, OrderingScheme> nodeOrderingMap = new HashMap<>();

  public DistributedPlanGenerator(MPPQueryContext queryContext, Analysis analysis) {
    this.queryId = queryContext.getQueryId();
    this.analysis = analysis;
  }

  public List<PlanNode> genResult(PlanNode node, PlanContext context) {
    List<PlanNode> res = node.accept(this, context);
    if (res.size() == 1) {
      return res;
    } else if (res.size() > 1) {
      CollectNode collectNode = new CollectNode(queryId.genPlanNodeId());
      collectNode.setOutputSymbols(res.get(0).getOutputSymbols());
      res.forEach(collectNode::addChild);
      return Collections.singletonList(collectNode);
    } else {
      throw new IllegalStateException("List<PlanNode>.size should >= 1, but now is 0");
    }
  }

  @Override
  public List<PlanNode> visitPlan(PlanNode node, DistributedPlanGenerator.PlanContext context) {
    if (node instanceof WritePlanNode) {
      return Collections.singletonList(node);
    }

    List<List<PlanNode>> children =
        node.getChildren().stream()
            .map(child -> child.accept(this, context))
            .collect(toImmutableList());

    PlanNode newNode = node.clone();
    for (List<PlanNode> planNodes : children) {
      planNodes.forEach(newNode::addChild);
    }
    return Collections.singletonList(newNode);
  }

  @Override
  public List<PlanNode> visitOutput(OutputNode node, PlanContext context) {
    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    OrderingScheme childOrdering = nodeOrderingMap.get(childrenNodes.get(0).getPlanNodeId());
    if (childOrdering != null) {
      nodeOrderingMap.put(node.getPlanNodeId(), childOrdering);
    }

    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    node.setChild(mergeChildrenViaCollectOrMergeSort(childOrdering, childrenNodes));
    return Collections.singletonList(node);
  }

  @Override
  public List<PlanNode> visitLimit(LimitNode node, PlanContext context) {
    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    OrderingScheme childOrdering = nodeOrderingMap.get(childrenNodes.get(0).getPlanNodeId());
    if (childOrdering != null) {
      nodeOrderingMap.put(node.getPlanNodeId(), childOrdering);
    }

    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    // push down LimitNode in distributed plan optimize rule
    node.setChild(mergeChildrenViaCollectOrMergeSort(childOrdering, childrenNodes));
    return Collections.singletonList(node);
  }

  @Override
  public List<PlanNode> visitOffset(OffsetNode node, PlanContext context) {
    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    OrderingScheme childOrdering = nodeOrderingMap.get(childrenNodes.get(0).getPlanNodeId());
    if (childOrdering != null) {
      nodeOrderingMap.put(node.getPlanNodeId(), childOrdering);
    }

    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    node.setChild(mergeChildrenViaCollectOrMergeSort(childOrdering, childrenNodes));
    return Collections.singletonList(node);
  }

  @Override
  public List<PlanNode> visitProject(ProjectNode node, PlanContext context) {
    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    OrderingScheme childOrdering = nodeOrderingMap.get(childrenNodes.get(0).getPlanNodeId());
    if (childOrdering != null) {
      nodeOrderingMap.put(node.getPlanNodeId(), childOrdering);
    }

    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    for (Expression expression : node.getAssignments().getMap().values()) {
      if (containsDiffFunction(expression)) {
        node.setChild(mergeChildrenViaCollectOrMergeSort(childOrdering, childrenNodes));
        return Collections.singletonList(node);
      }
    }

    List<PlanNode> resultNodeList = new ArrayList<>();
    for (int i = 0; i < childrenNodes.size(); i++) {
      PlanNode child = childrenNodes.get(i);
      ProjectNode subProjectNode =
          new ProjectNode(queryId.genPlanNodeId(), child, node.getAssignments());
      resultNodeList.add(subProjectNode);
      if (i == 0) {
        nodeOrderingMap.put(subProjectNode.getPlanNodeId(), childOrdering);
      }
    }
    return resultNodeList;
  }

  @Override
  public List<PlanNode> visitSort(SortNode node, PlanContext context) {
    context.expectedOrderingScheme = node.getOrderingScheme();
    context.hasSortNode = true;
    nodeOrderingMap.put(node.getPlanNodeId(), node.getOrderingScheme());

    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    // may have ProjectNode above SortNode later, so use MergeSortNode but not return SortNode list
    MergeSortNode mergeSortNode =
        new MergeSortNode(
            queryId.genPlanNodeId(), node.getOrderingScheme(), node.getOutputSymbols());
    for (PlanNode child : childrenNodes) {
      SortNode subSortNode =
          new SortNode(queryId.genPlanNodeId(), child, node.getOrderingScheme(), false);
      mergeSortNode.addChild(subSortNode);
    }
    nodeOrderingMap.put(mergeSortNode.getPlanNodeId(), mergeSortNode.getOrderingScheme());

    return Collections.singletonList(mergeSortNode);
  }

  @Override
  public List<PlanNode> visitFilter(FilterNode node, PlanContext context) {
    List<PlanNode> childrenNodes = node.getChild().accept(this, context);
    OrderingScheme childOrdering = nodeOrderingMap.get(childrenNodes.get(0).getPlanNodeId());
    if (childOrdering != null) {
      nodeOrderingMap.put(node.getPlanNodeId(), childOrdering);
    }

    if (childrenNodes.size() == 1) {
      node.setChild(childrenNodes.get(0));
      return Collections.singletonList(node);
    }

    if (containsDiffFunction(node.getPredicate())) {
      node.setChild(mergeChildrenViaCollectOrMergeSort(childOrdering, childrenNodes));
      return Collections.singletonList(node);
    }

    List<PlanNode> resultNodeList = new ArrayList<>();
    for (int i = 0; i < childrenNodes.size(); i++) {
      PlanNode child = childrenNodes.get(i);
      FilterNode subFilterNode =
          new FilterNode(queryId.genPlanNodeId(), child, node.getPredicate());
      resultNodeList.add(subFilterNode);
      if (i == 0) {
        nodeOrderingMap.put(subFilterNode.getPlanNodeId(), childOrdering);
      }
    }
    return resultNodeList;
  }

  @Override
  public List<PlanNode> visitTableScan(TableScanNode node, PlanContext context) {

    Map<TRegionReplicaSet, TableScanNode> tableScanNodeMap = new HashMap<>();

    for (DeviceEntry deviceEntry : node.getDeviceEntries()) {
      List<TRegionReplicaSet> regionReplicaSets =
          analysis
              .getDataPartitionInfo()
              .getDataRegionReplicaSetWithTimeFilter(
                  node.getQualifiedObjectName().getDatabaseName(),
                  deviceEntry.getDeviceID(),
                  node.getTimeFilter());
      for (TRegionReplicaSet regionReplicaSet : regionReplicaSets) {
        TableScanNode tableScanNode =
            tableScanNodeMap.computeIfAbsent(
                regionReplicaSet,
                k -> {
                  TableScanNode scanNode =
                      new TableScanNode(
                          queryId.genPlanNodeId(),
                          node.getQualifiedObjectName(),
                          node.getOutputSymbols(),
                          node.getAssignments(),
                          new ArrayList<>(),
                          node.getIdAndAttributeIndexMap(),
                          node.getScanOrder(),
                          node.getTimePredicate().orElse(null),
                          node.getPushDownPredicate());
                  scanNode.setRegionReplicaSet(regionReplicaSet);
                  return scanNode;
                });
        tableScanNode.appendDeviceEntry(deviceEntry);
      }
    }

    List<PlanNode> resultTableScanNodeList = new ArrayList<>();
    TRegionReplicaSet mostUsedDataRegion = null;
    int maxDeviceEntrySizeOfTableScan = 0;
    for (Map.Entry<TRegionReplicaSet, TableScanNode> entry : tableScanNodeMap.entrySet()) {
      TRegionReplicaSet regionReplicaSet = entry.getKey();
      TableScanNode subTableScanNode = entry.getValue();
      subTableScanNode.setPlanNodeId(queryId.genPlanNodeId());
      subTableScanNode.setRegionReplicaSet(regionReplicaSet);
      resultTableScanNodeList.add(subTableScanNode);

      if (mostUsedDataRegion == null
          || subTableScanNode.getDeviceEntries().size() > maxDeviceEntrySizeOfTableScan) {
        mostUsedDataRegion = regionReplicaSet;
        maxDeviceEntrySizeOfTableScan = subTableScanNode.getDeviceEntries().size();
      }
    }
    context.mostUsedDataRegion = mostUsedDataRegion;

    if (!context.hasSortNode) {
      return resultTableScanNodeList;
    }

    processSortProperty(node, resultTableScanNodeList, context);
    return resultTableScanNodeList;
  }

  private PlanNode mergeChildrenViaCollectOrMergeSort(
      OrderingScheme childOrdering, List<PlanNode> childrenNodes) {
    PlanNode firstChild = childrenNodes.get(0);

    // children has sort property, use MergeSort to merge children
    if (childOrdering != null) {
      MergeSortNode mergeSortNode =
          new MergeSortNode(queryId.genPlanNodeId(), childOrdering, firstChild.getOutputSymbols());
      childrenNodes.forEach(mergeSortNode::addChild);
      nodeOrderingMap.put(mergeSortNode.getPlanNodeId(), childOrdering);
      return mergeSortNode;
    }

    // children has no sort property, use CollectNode to merge children
    CollectNode collectNode = new CollectNode(queryId.genPlanNodeId());
    collectNode.setOutputSymbols(firstChild.getOutputSymbols());
    childrenNodes.forEach(collectNode::addChild);
    return collectNode;
  }

  private void processSortProperty(
      TableScanNode tableScanNode, List<PlanNode> resultTableScanNodeList, PlanContext context) {
    List<Symbol> newOrderingSymbols = new ArrayList<>();
    List<SortOrder> newSortOrders = new ArrayList<>();
    OrderingScheme expectedOrderingScheme = context.expectedOrderingScheme;

    for (Symbol symbol : expectedOrderingScheme.getOrderBy()) {
      if (TIMESTAMP_STR.equalsIgnoreCase(symbol.getName())) {
        if (!expectedOrderingScheme.getOrderings().get(symbol).isAscending()) {
          // TODO(beyyes) move scan order judgement into logical plan optimizer
          resultTableScanNodeList.forEach(
              node -> ((TableScanNode) node).setScanOrder(Ordering.DESC));
        }
        break;
      } else if (!tableScanNode.getIdAndAttributeIndexMap().containsKey(symbol)) {
        break;
      }

      newOrderingSymbols.add(symbol);
      newSortOrders.add(expectedOrderingScheme.getOrdering(symbol));
    }

    // no sort property can be pushed down into TableScanNode
    if (newOrderingSymbols.isEmpty()) {
      return;
    }

    List<Function<DeviceEntry, String>> orderingRules = new ArrayList<>();
    for (Symbol symbol : newOrderingSymbols) {
      int idx = tableScanNode.getIdAndAttributeIndexMap().get(symbol);
      if (tableScanNode.getAssignments().get(symbol).getColumnCategory()
          == TsTableColumnCategory.ID) {
        // segments[0] is always tableName
        orderingRules.add(deviceEntry -> (String) deviceEntry.getDeviceID().getSegments()[idx + 1]);
      } else {
        orderingRules.add(deviceEntry -> deviceEntry.getAttributeColumnValues().get(idx));
      }
    }

    Comparator<DeviceEntry> comparator;
    if (newSortOrders.get(0).isNullsFirst()) {
      comparator =
          newSortOrders.get(0).isAscending()
              ? Comparator.nullsFirst(Comparator.comparing(orderingRules.get(0)))
              : Comparator.nullsFirst(Comparator.comparing(orderingRules.get(0))).reversed();
    } else {
      comparator =
          newSortOrders.get(0).isAscending()
              ? Comparator.nullsLast(Comparator.comparing(orderingRules.get(0)))
              : Comparator.nullsLast(Comparator.comparing(orderingRules.get(0))).reversed();
    }
    for (int i = 1; i < orderingRules.size(); i++) {
      Comparator<DeviceEntry> thenComparator;
      if (newSortOrders.get(i).isNullsFirst()) {
        thenComparator =
            newSortOrders.get(i).isAscending()
                ? Comparator.nullsFirst(Comparator.comparing(orderingRules.get(i)))
                : Comparator.nullsFirst(Comparator.comparing(orderingRules.get(i))).reversed();
      } else {
        thenComparator =
            newSortOrders.get(i).isAscending()
                ? Comparator.nullsLast(Comparator.comparing(orderingRules.get(i)))
                : Comparator.nullsLast(Comparator.comparing(orderingRules.get(i))).reversed();
      }
      comparator = comparator.thenComparing(thenComparator);
    }

    OrderingScheme newOrderingScheme =
        new OrderingScheme(
            newOrderingSymbols,
            IntStream.range(0, newOrderingSymbols.size())
                .boxed()
                .collect(Collectors.toMap(newOrderingSymbols::get, newSortOrders::get)));
    for (PlanNode planNode : resultTableScanNodeList) {
      TableScanNode scanNode = (TableScanNode) planNode;
      nodeOrderingMap.put(scanNode.getPlanNodeId(), newOrderingScheme);
      scanNode.getDeviceEntries().sort(comparator);
    }
  }

  // ------------------- schema related interface ---------------------------------------------
  @Override
  public List<PlanNode> visitTableDeviceQuery(TableDeviceQueryNode node, PlanContext context) {
    String database = PathUtils.qualifyDatabaseName(node.getDatabase());
    Set<TRegionReplicaSet> schemaRegionSet = new HashSet<>();
    analysis
        .getSchemaPartitionInfo()
        .getSchemaPartitionMap()
        .get(database)
        .forEach(
            (deviceGroupId, schemaRegionReplicaSet) -> schemaRegionSet.add(schemaRegionReplicaSet));

    context.mostUsedDataRegion = schemaRegionSet.iterator().next();
    if (schemaRegionSet.size() == 1) {
      node.setRegionReplicaSet(schemaRegionSet.iterator().next());
      return Collections.singletonList(node);
    } else {
      List<PlanNode> res = new ArrayList<>(schemaRegionSet.size());
      for (TRegionReplicaSet schemaRegion : schemaRegionSet) {
        TableDeviceQueryNode clonedChild = (TableDeviceQueryNode) node.clone();
        clonedChild.setPlanNodeId(queryId.genPlanNodeId());
        clonedChild.setRegionReplicaSet(schemaRegion);
        res.add(clonedChild);
      }
      return res;
    }
  }

  @Override
  public List<PlanNode> visitTableDeviceFetch(TableDeviceFetchNode node, PlanContext context) {
    String database = PathUtils.qualifyDatabaseName(node.getDatabase());
    Set<TRegionReplicaSet> schemaRegionSet = new HashSet<>();
    SchemaPartition schemaPartition = analysis.getSchemaPartitionInfo();
    Map<TSeriesPartitionSlot, TRegionReplicaSet> databaseMap =
        schemaPartition.getSchemaPartitionMap().get(database);

    databaseMap.forEach(
        (deviceGroupId, schemaRegionReplicaSet) -> schemaRegionSet.add(schemaRegionReplicaSet));

    if (schemaRegionSet.size() == 1) {
      context.mostUsedDataRegion = schemaRegionSet.iterator().next();
      node.setRegionReplicaSet(context.mostUsedDataRegion);
      return Collections.singletonList(node);
    } else {
      Map<TRegionReplicaSet, TableDeviceFetchNode> tableDeviceFetchMap = new HashMap<>();

      for (Object[] deviceIdArray : node.getDeviceIdList()) {
        IDeviceID deviceID =
            IDeviceID.Factory.DEFAULT_FACTORY.create(parseDeviceIdArray(deviceIdArray));
        TRegionReplicaSet regionReplicaSet =
            databaseMap.get(schemaPartition.calculateDeviceGroupId(deviceID));
        tableDeviceFetchMap
            .computeIfAbsent(
                regionReplicaSet,
                k ->
                    new TableDeviceFetchNode(
                        queryId.genPlanNodeId(),
                        node.getDatabase(),
                        node.getTableName(),
                        new ArrayList<>(),
                        node.getColumnHeaderList(),
                        regionReplicaSet))
            .addDeviceId(deviceIdArray);
      }

      List<PlanNode> res = new ArrayList<>();
      TRegionReplicaSet mostUsedDataRegion = null;
      int maxDeviceEntrySizeOfTableScan = 0;
      for (Map.Entry<TRegionReplicaSet, TableDeviceFetchNode> entry :
          tableDeviceFetchMap.entrySet()) {
        TRegionReplicaSet regionReplicaSet = entry.getKey();
        TableDeviceFetchNode subTableDeviceFetchNode = entry.getValue();
        res.add(subTableDeviceFetchNode);

        if (subTableDeviceFetchNode.getDeviceIdList().size() > maxDeviceEntrySizeOfTableScan) {
          mostUsedDataRegion = regionReplicaSet;
          maxDeviceEntrySizeOfTableScan = subTableDeviceFetchNode.getDeviceIdList().size();
        }
      }
      context.mostUsedDataRegion = mostUsedDataRegion;
      return res;
    }
  }

  public static class PlanContext {
    final Map<PlanNodeId, NodeDistribution> nodeDistributionMap;
    boolean hasExchangeNode = false;
    boolean hasSortNode = false;
    OrderingScheme expectedOrderingScheme;
    TRegionReplicaSet mostUsedDataRegion;

    public PlanContext() {
      this.nodeDistributionMap = new HashMap<>();
    }

    public NodeDistribution getNodeDistribution(PlanNodeId nodeId) {
      return this.nodeDistributionMap.get(nodeId);
    }
  }
}
