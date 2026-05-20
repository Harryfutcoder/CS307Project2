package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AggregateOperator implements PhysicalOperator {
    private static class GroupState {
        Object[] states;

        GroupState(int size) {
            this.states = new Object[size];
        }
    }

    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions;
    private final ArrayList<ColumnMeta> outputSchema;
    private final List<TabCol> resultSchema;
    private final List<TempTuple> results;
    private int cursor;

    public AggregateOperator(PhysicalOperator child, List<SelectItem<?>> selectItems, GroupByElement groupByElement) {
        this.child = child;
        this.selectItems = selectItems;
        this.groupByExpressions = new ArrayList<>();
        if (groupByElement != null && groupByElement.getGroupByExpressionList() != null) {
            this.groupByExpressions.addAll(groupByElement.getGroupByExpressionList().getExpressions());
        }
        this.outputSchema = buildOutputSchema();
        this.resultSchema = new ArrayList<>();
        for (ColumnMeta columnMeta : outputSchema) {
            this.resultSchema.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        this.results = new ArrayList<>();
        this.cursor = -1;
    }

    @Override
    public boolean hasNext() {
        return cursor + 1 < results.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        cursor = -1;
        results.clear();

        Map<String, GroupState> groupedStates = new LinkedHashMap<>();
        boolean hasRow = false;
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            hasRow = true;
            String key = buildGroupKey(tuple);
            GroupState groupState = groupedStates.computeIfAbsent(key, k -> new GroupState(selectItems.size()));
            consumeTuple(tuple, groupState);
        }

        if (!hasRow && groupByExpressions.isEmpty()) {
            GroupState emptyGroup = new GroupState(selectItems.size());
            groupedStates.put("__empty__", emptyGroup);
        }

        for (GroupState groupState : groupedStates.values()) {
            ArrayList<Value> row = new ArrayList<>();
            for (int i = 0; i < selectItems.size(); i++) {
                Expression expression = selectItems.get(i).getExpression();
                Object state = groupState.states[i];
                if (expression instanceof Function function) {
                    String fnName = function.getName().toLowerCase();
                    if ("count".equals(fnName)) {
                        long countValue = state == null ? 0L : (Long) state;
                        row.add(new Value(countValue));
                    } else {
                        row.add((Value) state);
                    }
                } else {
                    row.add((Value) state);
                }
            }
            results.add(new TempTuple(row, resultSchema));
        }
    }

    private String buildGroupKey(Tuple tuple) throws DBException {
        if (groupByExpressions.isEmpty()) {
            return "__all__";
        }
        StringBuilder sb = new StringBuilder();
        for (Expression expression : groupByExpressions) {
            Value value = evaluateExpression(tuple, expression);
            if (value == null) {
                sb.append("null|");
            } else {
                sb.append(value.type).append(":").append(value.value).append("|");
            }
        }
        return sb.toString();
    }

    private void consumeTuple(Tuple tuple, GroupState groupState) throws DBException {
        for (int i = 0; i < selectItems.size(); i++) {
            Expression expression = selectItems.get(i).getExpression();
            if (expression instanceof Function function) {
                String fnName = function.getName().toLowerCase();
                if ("count".equals(fnName)) {
                    long oldCount = groupState.states[i] == null ? 0L : (Long) groupState.states[i];
                    groupState.states[i] = oldCount + 1;
                } else if ("max".equals(fnName) || "min".equals(fnName)) {
                    Expression paramExpr = extractSingleFunctionParameter(function);
                    Value newValue = evaluateExpression(tuple, paramExpr);
                    if (newValue == null) {
                        continue;
                    }
                    Value oldValue = (Value) groupState.states[i];
                    if (oldValue == null) {
                        groupState.states[i] = newValue;
                    } else {
                        int cmp = ValueComparer.compare(newValue, oldValue);
                        if ("max".equals(fnName) && cmp > 0) {
                            groupState.states[i] = newValue;
                        } else if ("min".equals(fnName) && cmp < 0) {
                            groupState.states[i] = newValue;
                        }
                    }
                } else {
                    throw new DBException(ExceptionTypes.NotSupportedOperation(function));
                }
            } else {
                if (groupState.states[i] == null) {
                    groupState.states[i] = evaluateExpression(tuple, expression);
                }
            }
        }
    }

    private Expression extractSingleFunctionParameter(Function function) throws DBException {
        ExpressionList<?> parameters = function.getParameters();
        if (parameters == null || parameters.isEmpty()) {
            throw new DBException(ExceptionTypes.InvalidSQL("SELECT", "Function missing parameter: " + function));
        }
        return parameters.get(0);
    }

    private Value evaluateExpression(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Column column) {
            return resolveColumnValue(tuple, column);
        }
        if (expr instanceof StringValue stringValue) {
            return new Value(stringValue.getValue());
        }
        if (expr instanceof LongValue longValue) {
            return new Value(longValue.getValue());
        }
        if (expr instanceof DoubleValue doubleValue) {
            return new Value(doubleValue.getValue());
        }
        if (expr instanceof SignedExpression signedExpression) {
            String sign = String.valueOf(signedExpression.getSign());
            Value inner = evaluateExpression(tuple, signedExpression.getExpression());
            if (inner == null || !"-".equals(sign)) {
                return inner;
            }
            if (inner.type == ValueType.INTEGER) {
                return new Value(-((Long) inner.value));
            }
            if (inner.type == ValueType.FLOAT) {
                return new Value(-((Double) inner.value));
            }
            throw new DBException(ExceptionTypes.NotSupportedOperation(signedExpression));
        }
        throw new DBException(ExceptionTypes.NotSupportedOperation(expr));
    }

    private Value resolveColumnValue(Tuple tuple, Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        if (tableName != null && !tableName.isBlank()) {
            return tuple.getValue(new TabCol(tableName, columnName));
        }
        Value found = null;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                Value candidate = tuple.getValue(tabCol);
                if (candidate != null) {
                    if (found != null) {
                        throw new DBException(ExceptionTypes.InvalidSQL("SELECT",
                                "Ambiguous column in aggregate: " + columnName));
                    }
                    found = candidate;
                }
            }
        }
        return found;
    }

    private ArrayList<ColumnMeta> buildOutputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            String outputName = selectItem.getAliasName();
            if (outputName == null || outputName.isBlank()) {
                outputName = expression.toString();
            }
            ValueType outputType = inferType(expression);
            int len = switch (outputType) {
                case INTEGER -> Value.INT_SIZE;
                case FLOAT -> Value.FLOAT_SIZE;
                case CHAR -> Value.CHAR_SIZE;
                default -> Value.CHAR_SIZE;
            };
            schema.add(new ColumnMeta("agg", outputName, outputType, len, offset));
            offset += len;
        }
        return schema;
    }

    private ValueType inferType(Expression expression) {
        if (expression instanceof Function function) {
            String fnName = function.getName().toLowerCase();
            if ("count".equals(fnName)) {
                return ValueType.INTEGER;
            }
            if ("max".equals(fnName) || "min".equals(fnName)) {
                try {
                    Expression param = extractSingleFunctionParameter(function);
                    return inferType(param);
                } catch (DBException e) {
                    return ValueType.CHAR;
                }
            }
            return ValueType.CHAR;
        }
        if (expression instanceof Column column) {
            String tableName = column.getTableName();
            String columnName = column.getColumnName();
            for (ColumnMeta columnMeta : child.outputSchema()) {
                boolean nameMatch = columnMeta.name.equalsIgnoreCase(columnName);
                boolean tableMatch = tableName == null || tableName.isBlank()
                        || columnMeta.tableName.equalsIgnoreCase(tableName);
                if (nameMatch && tableMatch) {
                    return columnMeta.type;
                }
            }
            return ValueType.CHAR;
        }
        if (expression instanceof LongValue) {
            return ValueType.INTEGER;
        }
        if (expression instanceof DoubleValue) {
            return ValueType.FLOAT;
        }
        return ValueType.CHAR;
    }

    @Override
    public void Next() {
        cursor++;
    }

    @Override
    public Tuple Current() {
        if (cursor < 0 || cursor >= results.size()) {
            return null;
        }
        return results.get(cursor);
    }

    @Override
    public void Close() {
        child.Close();
        results.clear();
        cursor = -1;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }
}
