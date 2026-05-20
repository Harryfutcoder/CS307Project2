package edu.sustech.cs307.optimizer;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.physicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PhysicalPlanner {
    public static PhysicalOperator generateOperator(DBManager dbManager, LogicalOperator logicalOp) throws DBException {
        if (logicalOp instanceof LogicalTableScanOperator tableScanOperator) {
            return handleTableScan(dbManager, tableScanOperator);
        } else if (logicalOp instanceof LogicalFilterOperator filterOperator) {
            return handleFilter(dbManager, filterOperator);
        } else if (logicalOp instanceof LogicalJoinOperator joinOperator) {
            return handleJoin(dbManager, joinOperator);
        } else if (logicalOp instanceof LogicalProjectOperator projectOperator) {
            return handleProject(dbManager, projectOperator);
        } else if (logicalOp instanceof LogicalInsertOperator insertOperator) {
            return handleInsert(dbManager, insertOperator);
        } else if (logicalOp instanceof LogicalUpdateOperator updateOperator) {
            return handleUpdate(dbManager, updateOperator);
        } else if (logicalOp instanceof LogicalDeleteOperator deleteOperator) {
            return handleDelete(dbManager, deleteOperator);
        } else if (logicalOp instanceof LogicalSortOperator sortOperator) {
            return handleSort(dbManager, sortOperator);
        } else if (logicalOp instanceof LogicalAggregateOperator aggregateOperator) {
            return handleAggregate(dbManager, aggregateOperator);
        }

        else {
            throw new DBException(ExceptionTypes.UnsupportedOperator(logicalOp.getClass().getSimpleName()));
        }
    }

    private static PhysicalOperator handleTableScan(DBManager dbManager, LogicalTableScanOperator logicalTableScanOp) {
        String tableName = logicalTableScanOp.getTableName();
        TableMeta tableMeta;
        try {
            tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            // Fallback to SeqScan if TableMeta cannot be retrieved
            return new SeqScanOperator(tableName, dbManager);
        }

        return new SeqScanOperator(tableName, dbManager);
    }

    private static PhysicalOperator handleFilter(DBManager dbManager, LogicalFilterOperator logicalFilterOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalFilterOp.getChild());
        if (inputOp instanceof SeqScanOperator seqScanOperator) {
            PhysicalOperator indexedScan = tryBuildIndexedScan(dbManager, seqScanOperator, logicalFilterOp.getWhereExpr());
            if (indexedScan != null) {
                return new FilterOperator(indexedScan, logicalFilterOp.getWhereExpr(), dbManager);
            }
        }
        return new FilterOperator(inputOp, logicalFilterOp.getWhereExpr(), dbManager);
    }

    private static PhysicalOperator handleJoin(DBManager dbManager, LogicalJoinOperator logicalJoinOp)
            throws DBException {
        PhysicalOperator leftOp = generateOperator(dbManager, logicalJoinOp.getLeftInput());
        PhysicalOperator rightOp = generateOperator(dbManager, logicalJoinOp.getRightInput());
        return new NestedLoopJoinOperator(leftOp, rightOp, logicalJoinOp.getJoinExprs());
    }

    private static PhysicalOperator handleProject(DBManager dbManager, LogicalProjectOperator logicalProjectOp)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalProjectOp.getChild());
        return new ProjectOperator(inputOp, logicalProjectOp.getOutputSchema());
    }

    private static PhysicalOperator handleSort(DBManager dbManager, LogicalSortOperator logicalSortOperator)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalSortOperator.getChild());
        return new SortOperator(inputOp, logicalSortOperator.getOrderByElements());
    }

    private static PhysicalOperator handleAggregate(DBManager dbManager, LogicalAggregateOperator logicalAggregateOperator)
            throws DBException {
        PhysicalOperator inputOp = generateOperator(dbManager, logicalAggregateOperator.getChild());
        return new AggregateOperator(inputOp, logicalAggregateOperator.getSelectItems(),
                logicalAggregateOperator.getGroupByElement());
    }

    /**
     * 处理将逻辑插入操作转换为物理插入运算符的过程
     * 
     * @param dbManager       提供数据库操作访问的数据库管理器实例
     * @param logicalInsertOp 需要被转换的逻辑插入运算符
     * @return 准备好执行的物理插入运算符
     * @throws DBException 如果存在列不匹配、类型不匹配或无效SQL语法时抛出
     */
    @SuppressWarnings("deprecation") // for ExpressionList<?>::getExpressions
    private static PhysicalOperator handleInsert(DBManager dbManager, LogicalInsertOperator logicalInsertOp)
            throws DBException {
        var tableMeta = dbManager.getMetaManager().getTable(logicalInsertOp.tableName);
        List<String> columns = new ArrayList<>();
        if (logicalInsertOp.columns != null) {
            if (tableMeta.columns_list.size() != logicalInsertOp.columns.size()) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            Set<String> seenColumns = new HashSet<>();
            for (int i = 0; i < logicalInsertOp.columns.size(); i++) {
                String colName = logicalInsertOp.columns.get(i).getColumnName();
                if (tableMeta.getColumnMeta(colName) == null) {
                    throw new DBException(ExceptionTypes.ColumnDoesNotExist(colName));
                }
                String lowerColName = colName.toLowerCase();
                if (!seenColumns.add(lowerColName)) {
                    throw new DBException(ExceptionTypes.InsertColumnNameMismatch());
                }
                columns.add(colName);
            }
        } else {
            for (ColumnMeta columnMeta : tableMeta.columns_list) {
                columns.add(columnMeta.name);
            }
        }
        if (!(logicalInsertOp.values instanceof Values)) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Values must be an expression list"));
        }
        ExpressionList<?> rawValues = ((Values) logicalInsertOp.values).getExpressions();
        if (rawValues == null || rawValues.isEmpty()) {
            throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
        }

        List<List<Expression>> rows = new ArrayList<>();
        Expression firstExpr = rawValues.get(0);
        if (firstExpr instanceof ParenthesedExpressionList<?>) {
            for (Expression expr : rawValues) {
                if (!(expr instanceof ParenthesedExpressionList<?> rowExpressions)) {
                    throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
                }
                rows.add(new ArrayList<>(rowExpressions.getExpressions()));
            }
        } else {
            rows.add(new ArrayList<>(rawValues.getExpressions()));
        }

        List<Value> values = new ArrayList<>();
        for (List<Expression> rowExpressions : rows) {
            if (rowExpressions.size() != columns.size()) {
                throw new DBException(ExceptionTypes.InsertColumnSizeMismatch());
            }
            Map<String, Expression> rowValueByColumn = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                rowValueByColumn.put(columns.get(i), rowExpressions.get(i));
            }
            for (ColumnMeta schemaColumn : tableMeta.columns_list) {
                Expression valueExpr = rowValueByColumn.get(schemaColumn.name);
                if (valueExpr == null) {
                    throw new DBException(ExceptionTypes.InsertColumnNameMismatch());
                }
                values.add(parseInsertValue(valueExpr, schemaColumn.type));
            }
        }

        return new InsertOperator(logicalInsertOp.tableName, columns,
                values, dbManager);
    }

    private static Value parseInsertValue(Expression expr, ValueType expectedType)
            throws DBException {
        if (expr instanceof SignedExpression signedExpression) {
            String sign = String.valueOf(signedExpression.getSign());
            Expression innerExpr = signedExpression.getExpression();
            if ("-".equals(sign)) {
                if (innerExpr instanceof LongValue longValue) {
                    return parseInsertValue(new LongValue(-longValue.getValue()), expectedType);
                }
                if (innerExpr instanceof DoubleValue doubleValue) {
                    return parseInsertValue(new DoubleValue(-doubleValue.getValue()), expectedType);
                }
            }
            return parseInsertValue(innerExpr, expectedType);
        }
        if (expr instanceof StringValue stringValue) {
            if (expectedType != ValueType.CHAR) {
                throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
            }
            String valueString = stringValue.getValue();
            if (valueString.length() > Value.CHAR_SIZE) {
                valueString = valueString.substring(0, Value.CHAR_SIZE);
            }
            return new Value(valueString);
        }
        if (expr instanceof DoubleValue floatValue) {
            if (expectedType != ValueType.FLOAT) {
                throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
            }
            return new Value(floatValue.getValue());
        }
        if (expr instanceof LongValue longValue) {
            if (expectedType == ValueType.INTEGER) {
                return new Value(longValue.getValue());
            }
            if (expectedType == ValueType.FLOAT) {
                return new Value((double) longValue.getValue());
            }
            throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
        }
        throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported value type in VALUES clause"));
    }


    private static PhysicalOperator handleUpdate(DBManager dbManager, LogicalUpdateOperator logicalUpdateOp) throws DBException {
        // TODO: Implement handleUpdate
        PhysicalOperator scanner = generateOperator(dbManager, logicalUpdateOp.getChild());
        if (logicalUpdateOp.getColumns().size() != 1 ) {
            throw new DBException(ExceptionTypes.InvalidSQL("INSERT", "Unsupported expression list"));
        }
        return new UpdateOperator(scanner, dbManager, logicalUpdateOp.getTableName(),
                logicalUpdateOp.getColumns().get(0), logicalUpdateOp.getExpression());
    }

    private static PhysicalOperator handleDelete(DBManager dbManager, LogicalDeleteOperator logicalDeleteOp) throws DBException {
        PhysicalOperator scanner = generateOperator(dbManager, logicalDeleteOp.getChild());
        return new DeleteOperator(scanner, logicalDeleteOp.getWhereExpr());
    }

    private static PhysicalOperator tryBuildIndexedScan(DBManager dbManager, SeqScanOperator scanOperator,
                                                         Expression whereExpr) throws DBException {
        if (!(whereExpr instanceof BinaryExpression binaryExpression)) {
            return null;
        }
        if (!(binaryExpression instanceof EqualsTo
                || binaryExpression instanceof GreaterThan
                || binaryExpression instanceof GreaterThanEquals
                || binaryExpression instanceof MinorThan
                || binaryExpression instanceof MinorThanEquals)) {
            return null;
        }

        Column indexedColumn;
        Expression constantExpr;
        String operator = binaryExpression.getStringExpression();
        if (binaryExpression.getLeftExpression() instanceof Column leftColumn) {
            indexedColumn = leftColumn;
            constantExpr = binaryExpression.getRightExpression();
        } else if (binaryExpression.getRightExpression() instanceof Column rightColumn) {
            indexedColumn = rightColumn;
            constantExpr = binaryExpression.getLeftExpression();
            operator = reverseOperator(operator);
        } else {
            return null;
        }

        Value constantValue = parseComparableConstant(constantExpr);
        if (constantValue == null) {
            return null;
        }

        String tableName = scanOperator.getTableName();
        String indexName = dbManager.findIndexOnColumn(tableName, indexedColumn.getColumnName());
        if (indexName == null) {
            return null;
        }

        var index = dbManager.loadIndex(tableName, indexName);
        ArrayList<RID> rids = new ArrayList<>();
        switch (operator) {
            case "=" -> {
                RID rid = index.EqualTo(constantValue);
                if (rid != null) {
                    rids.add(rid);
                }
            }
            case ">" -> collectRids(rids, index.MoreThan(constantValue, false));
            case ">=" -> collectRids(rids, index.MoreThan(constantValue, true));
            case "<" -> collectRids(rids, index.LessThan(constantValue, false));
            case "<=" -> collectRids(rids, index.LessThan(constantValue, true));
            default -> {
                return null;
            }
        }
        return new IndexScanOperator(tableName, dbManager, rids);
    }

    private static void collectRids(List<RID> rids, Iterator<Map.Entry<Value, RID>> iterator) {
        while (iterator.hasNext()) {
            rids.add(iterator.next().getValue());
        }
    }

    private static String reverseOperator(String operator) {
        return switch (operator) {
            case ">" -> "<";
            case ">=" -> "<=";
            case "<" -> ">";
            case "<=" -> ">=";
            default -> operator;
        };
    }

    private static Value parseComparableConstant(Expression expression) {
        if (expression instanceof StringValue stringValue) {
            return new Value(stringValue.getValue());
        }
        if (expression instanceof LongValue longValue) {
            return new Value(longValue.getValue());
        }
        if (expression instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue());
        }
        if (expression instanceof SignedExpression signedExpression) {
            String sign = String.valueOf(signedExpression.getSign());
            Value inner = parseComparableConstant(signedExpression.getExpression());
            if (inner == null || !"-".equals(sign)) {
                return inner;
            }
            if (inner.type == ValueType.INTEGER) {
                return new Value(-((Long) inner.value));
            }
            if (inner.type == ValueType.FLOAT) {
                return new Value(-((Double) inner.value));
            }
        }
        return null;
    }
}
