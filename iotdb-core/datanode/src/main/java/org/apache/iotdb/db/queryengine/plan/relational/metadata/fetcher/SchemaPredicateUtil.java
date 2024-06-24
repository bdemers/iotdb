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

package org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher;

import org.apache.iotdb.commons.schema.table.TsTable;
import org.apache.iotdb.db.queryengine.plan.relational.analyzer.predicate.schema.CheckSchemaPredicateVisitor;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.ComparisonExpression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Expression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Identifier;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.Literal;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.LogicalExpression;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.SymbolReference;

import org.apache.tsfile.utils.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SchemaPredicateUtil {

  static Pair<List<Expression>, List<Expression>> separateIdDeterminedPredicate(
      List<Expression> expressionList, TsTable table) {
    List<Expression> idDeterminedList = new ArrayList<>();
    List<Expression> idFuzzyList = new ArrayList<>();
    CheckSchemaPredicateVisitor visitor = new CheckSchemaPredicateVisitor();
    CheckSchemaPredicateVisitor.Context context = new CheckSchemaPredicateVisitor.Context(table);
    for (Expression expression : expressionList) {
      if (expression == null) {
        continue;
      }
      if (expression.accept(visitor, context)) {
        idFuzzyList.add(expression);
      } else {
        idDeterminedList.add(expression);
      }
    }
    return new Pair<>(idDeterminedList, idFuzzyList);
  }

  // input and-concat filter list
  // return or concat filter list, inner which all filter is and concat
  // e.g. (a OR b) AND (c OR d) -> (a AND c) OR (a AND d) OR (b AND c) OR (b AND d)
  // if input is empty, then return [[]]
  static List<List<Expression>> convertDeviceIdPredicateToOrConcatList(
      List<Expression> schemaFilterList) {
    List<List<Expression>> orConcatList =
        schemaFilterList.stream()
            .map(SchemaPredicateUtil::convertOneDeviceIdPredicateToOrConcat)
            .collect(Collectors.toList());
    int orSize = orConcatList.size();
    int finalResultSize = 1;
    for (List<Expression> filterList : orConcatList) {
      finalResultSize *= filterList.size();
    }
    List<List<Expression>> result = new ArrayList<>(finalResultSize);
    int[] indexes = new int[orSize]; // index count, each case represents one possible result
    Set<String> checkedColumnNames = new HashSet<>();
    boolean hasConflictFilter;
    Expression currentExpression;
    String currentColumnName;
    while (finalResultSize > 0) {
      checkedColumnNames.clear();
      hasConflictFilter = false;
      List<Expression> oneCase = new ArrayList<>(orConcatList.size());
      for (int j = 0; j < orSize; j++) {
        currentExpression = orConcatList.get(j).get(indexes[j]);
        currentColumnName = getColumnName(currentExpression);
        if (checkedColumnNames.contains(currentColumnName)) {
          hasConflictFilter = true;
          break;
        }
        checkedColumnNames.add(currentColumnName);
        oneCase.add(currentExpression);
      }

      if (!hasConflictFilter) {
        result.add(oneCase);
      }

      for (int k = orSize - 1; k >= 0; k--) {
        indexes[k]++;
        if (indexes[k] < orConcatList.get(k).size()) {
          break;
        }
        indexes[k] = 0;
      }
      finalResultSize--;
    }
    return result;
  }

  private static List<Expression> convertOneDeviceIdPredicateToOrConcat(Expression schemaFilter) {
    List<Expression> result = new ArrayList<>();
    if (schemaFilter instanceof LogicalExpression) {
      LogicalExpression logicalExpression = (LogicalExpression) schemaFilter;
      if (logicalExpression.getOperator().equals(LogicalExpression.Operator.AND)) {
        throw new IllegalStateException("Input filter shall not be AND operation");
      } else if (logicalExpression.getOperator().equals(LogicalExpression.Operator.OR)) {
        result.addAll(convertOneDeviceIdPredicateToOrConcat(logicalExpression.getTerms().get(0)));
        result.addAll(convertOneDeviceIdPredicateToOrConcat(logicalExpression.getTerms().get(1)));
      }
    } else {
      result.add(schemaFilter);
    }
    return result;
  }

  private static String getColumnName(Expression expression) {
    ComparisonExpression comparisonExpression = (ComparisonExpression) expression;
    String columnName;
    if (comparisonExpression.getLeft() instanceof Literal) {
      if (comparisonExpression.getRight() instanceof Identifier) {
        columnName = ((Identifier) (comparisonExpression.getRight())).getValue();
      } else {
        columnName = ((SymbolReference) (comparisonExpression.getRight())).getName();
      }
    } else {
      if (comparisonExpression.getLeft() instanceof Identifier) {
        columnName = ((Identifier) (comparisonExpression.getLeft())).getValue();
      } else {
        columnName = ((SymbolReference) (comparisonExpression.getLeft())).getName();
      }
    }
    return columnName;
  }

  static List<Integer> extractIdSingleMatchExpressionCases(
      List<List<Expression>> idDeterminedExpressionList, TsTable tableInstance) {
    List<Integer> selectedExpressionCases = new ArrayList<>();
    int idCount = tableInstance.getIdNums();
    for (int i = 0; i < idDeterminedExpressionList.size(); i++) {
      if (idDeterminedExpressionList.get(i).size() == idCount) {
        selectedExpressionCases.add(i);
      }
    }
    return selectedExpressionCases;
  }

  // compact and-concat expression list to one expression
  static Expression compactDeviceIdFuzzyPredicate(List<Expression> expressionList) {
    if (expressionList.isEmpty()) {
      return null;
    }
    LogicalExpression andExpression;
    Expression latestExpression = expressionList.get(0);
    for (int i = 1; i < expressionList.size(); i++) {
      andExpression =
          new LogicalExpression(
              LogicalExpression.Operator.AND,
              Arrays.asList(latestExpression, expressionList.get(i)));
      latestExpression = andExpression;
    }
    return latestExpression;
  }
}
