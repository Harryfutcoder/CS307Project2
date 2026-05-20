package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

public class NestedLoopJoinOperator implements PhysicalOperator {

    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    private final Collection<Expression> expr;

    private final ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
    private final List<Tuple> joinedTuples = new ArrayList<>();
    private int cursor = -1;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
        initOutputSchema();
    }

    @Override
    public boolean hasNext() {
        return cursor + 1 < joinedTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        leftOperator.Begin();
        rightOperator.Begin();
        joinedTuples.clear();
        cursor = -1;

        ArrayList<Tuple> leftTuples = new ArrayList<>();
        while (leftOperator.hasNext()) {
            leftOperator.Next();
            Tuple tuple = leftOperator.Current();
            if (tuple != null) {
                leftTuples.add(tuple);
            }
        }

        ArrayList<Tuple> rightTuples = new ArrayList<>();
        while (rightOperator.hasNext()) {
            rightOperator.Next();
            Tuple tuple = rightOperator.Current();
            if (tuple != null) {
                rightTuples.add(tuple);
            }
        }

        TabCol[] joinedSchema = outputSchema.stream()
                .map(col -> new TabCol(col.tableName, col.name))
                .toArray(TabCol[]::new);

        for (Tuple leftTuple : leftTuples) {
            for (Tuple rightTuple : rightTuples) {
                JoinTuple candidate = new JoinTuple(leftTuple, rightTuple, joinedSchema);
                if (matchJoinConditions(candidate)) {
                    joinedTuples.add(candidate);
                }
            }
        }
    }

    @Override
    public void Next() {
        cursor++;
    }

    @Override
    public Tuple Current() {
        if (cursor < 0 || cursor >= joinedTuples.size()) {
            return null;
        }
        return joinedTuples.get(cursor);
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        joinedTuples.clear();
        cursor = -1;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }

    private void initOutputSchema() {
        int offset = 0;
        for (ColumnMeta meta : leftOperator.outputSchema()) {
            outputSchema.add(new ColumnMeta(meta.tableName, meta.name, meta.type, meta.len, offset));
            offset += meta.len;
        }
        for (ColumnMeta meta : rightOperator.outputSchema()) {
            outputSchema.add(new ColumnMeta(meta.tableName, meta.name, meta.type, meta.len, offset));
            offset += meta.len;
        }
    }

    private boolean matchJoinConditions(Tuple candidate) throws DBException {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        for (Expression condition : expr) {
            if (!candidate.eval_expr(condition)) {
                return false;
            }
        }
        return true;
    }
}
