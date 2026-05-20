package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

public abstract class Tuple {
    public abstract Value getValue(TabCol tabCol) throws DBException;

    public abstract TabCol[] getTupleSchema();

    public abstract Value[] getValues() throws DBException;

    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) throws DBException {
        if (whereExpr == null) {
            return true;
        }
        if (whereExpr instanceof ParenthesedExpressionList<?> expressionList && expressionList.size() == 1) {
            return evaluateCondition(tuple, expressionList.get(0));
        }
        if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression());
        }
        if (whereExpr instanceof AndExpression andExpr) {
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        }
        if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        }
        if (whereExpr instanceof NotExpression notExpr) {
            return !evaluateCondition(tuple, notExpr.getExpression());
        }
        if (whereExpr instanceof InExpression inExpression) {
            return evaluateInExpression(tuple, inExpression);
        }
        if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        }
        return true;
    }

    private boolean evaluateInExpression(Tuple tuple, InExpression inExpression) throws DBException {
        Value leftValue = evaluateValue(tuple, inExpression.getLeftExpression());
        if (leftValue == null) {
            return false;
        }

        Expression rightExpr = inExpression.getRightExpression();
        if (rightExpr instanceof ParenthesedExpressionList<?> expressionList) {
            boolean found = false;
            for (Expression expression : expressionList) {
                Value item = evaluateValue(tuple, expression);
                if (item != null && ValueComparer.compare(leftValue, item) == 0) {
                    found = true;
                    break;
                }
            }
            return inExpression.isNot() ? !found : found;
        }
        throw new DBException(ExceptionTypes.InvalidSQL("SELECT", "Unsupported IN clause: " + inExpression));
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Expression leftExpr = binaryExpr.getLeftExpression();
        Expression rightExpr = binaryExpr.getRightExpression();
        Value leftValue = evaluateValue(tuple, leftExpr);
        Value rightValue = evaluateValue(tuple, rightExpr);
        if (leftValue == null || rightValue == null) {
            return false;
        }
        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        return switch (binaryExpr.getStringExpression()) {
            case "=" -> comparisonResult == 0;
            case "!=" -> comparisonResult != 0;
            case "<>" -> comparisonResult != 0;
            case ">" -> comparisonResult > 0;
            case ">=" -> comparisonResult >= 0;
            case "<" -> comparisonResult < 0;
            case "<=" -> comparisonResult <= 0;
            default -> throw new DBException(ExceptionTypes.NotSupportedOperation(binaryExpr));
        };
    }

    private Value evaluateValue(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Column column) {
            return resolveColumnValue(tuple, column);
        }
        return getConstantValue(expr);
    }

    private Value resolveColumnValue(Tuple tuple, Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        if (tableName != null && !tableName.isBlank()) {
            return tuple.getValue(new TabCol(tableName, columnName));
        }

        Value found = null;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (tabCol != null && tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                Value candidate = tuple.getValue(tabCol);
                if (candidate != null) {
                    if (found != null) {
                        throw new DBException(ExceptionTypes.InvalidSQL("SELECT",
                                "Ambiguous column reference: " + columnName));
                    }
                    found = candidate;
                }
            }
        }
        return found;
    }

    private Value getConstantValue(Expression expr) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        }
        if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        }
        if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        if (expr instanceof SignedExpression signedExpression) {
            String sign = String.valueOf(signedExpression.getSign());
            Value inner = getConstantValue(signedExpression.getExpression());
            if (inner == null || !"-".equals(sign)) {
                return inner;
            }
            if (inner.type == ValueType.INTEGER) {
                return new Value(-((Long) inner.value));
            }
            if (inner.type == ValueType.FLOAT) {
                return new Value(-((Double) inner.value));
            }
            throw new DBException(ExceptionTypes.InsertColumnTypeMismatch());
        }
        return null;
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        } else if (expr instanceof Column) {
            Column col = (Column) expr;
            return resolveColumnValue(this, col);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
        }
    }

}
