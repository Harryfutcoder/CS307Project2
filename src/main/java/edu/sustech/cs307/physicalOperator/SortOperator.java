package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SortOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<OrderByElement> orderByElements;
    private final List<Tuple> sortedTuples = new ArrayList<>();
    private int cursor = -1;

    public SortOperator(PhysicalOperator child, List<OrderByElement> orderByElements) {
        this.child = child;
        this.orderByElements = orderByElements;
    }

    @Override
    public boolean hasNext() {
        return cursor + 1 < sortedTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        sortedTuples.clear();
        cursor = -1;
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                sortedTuples.add(tuple);
            }
        }
        sortedTuples.sort(buildComparator());
    }

    private Comparator<Tuple> buildComparator() {
        return (left, right) -> {
            try {
                for (OrderByElement element : orderByElements) {
                    Expression expression = element.getExpression();
                    if (!(expression instanceof Column column)) {
                        continue;
                    }
                    Value lv = resolveColumnValue(left, column);
                    Value rv = resolveColumnValue(right, column);
                    int cmp = ValueComparer.compare(lv, rv);
                    if (cmp != 0) {
                        return element.isAscDescPresent() && !element.isAsc() ? -cmp : cmp;
                    }
                }
                return 0;
            } catch (DBException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Value resolveColumnValue(Tuple tuple, Column column) throws DBException {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();
        if (tableName != null && !tableName.isBlank()) {
            return tuple.getValue(new TabCol(tableName, columnName));
        }
        Value found = null;
        TabCol[] schema = tuple.getTupleSchema();
        if (schema == null) {
            return null;
        }
        for (TabCol tabCol : schema) {
            if (tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                Value candidate = tuple.getValue(tabCol);
                if (candidate != null) {
                    found = candidate;
                    break;
                }
            }
        }
        return found;
    }

    @Override
    public void Next() {
        cursor++;
    }

    @Override
    public Tuple Current() {
        if (cursor < 0 || cursor >= sortedTuples.size()) {
            return null;
        }
        return sortedTuples.get(cursor);
    }

    @Override
    public void Close() {
        child.Close();
        sortedTuples.clear();
        cursor = -1;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
}
