package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InMemoryIndexScanOperator implements PhysicalOperator {

    private final InMemoryOrderedIndex index;
    private final List<Map.Entry<Value, RID>> entries = new ArrayList<>();
    private final ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
    private int cursor = -1;
    private Tuple currentTuple;

    public InMemoryIndexScanOperator(InMemoryOrderedIndex index) {
        this.index = index;
        outputSchema.add(new ColumnMeta("index", "key", ValueType.CHAR, Value.CHAR_SIZE, 0));
        outputSchema.add(new ColumnMeta("index", "pageNum", ValueType.INTEGER, Value.INT_SIZE, Value.CHAR_SIZE));
        outputSchema.add(new ColumnMeta("index", "slotNum", ValueType.INTEGER, Value.INT_SIZE,
                Value.CHAR_SIZE + Value.INT_SIZE));
    }

    @Override
    public boolean hasNext() {
        return cursor + 1 < entries.size();
    }

    @Override
    public void Begin() throws DBException {
        entries.clear();
        var iterator = index.all();
        while (iterator.hasNext()) {
            entries.add(iterator.next());
        }
        cursor = -1;
        currentTuple = null;
    }

    @Override
    public void Next() {
        cursor++;
        if (cursor < 0 || cursor >= entries.size()) {
            currentTuple = null;
            return;
        }
        Map.Entry<Value, RID> entry = entries.get(cursor);
        ArrayList<Value> row = new ArrayList<>();
        row.add(new Value(entry.getKey().toString()));
        row.add(new Value((long) entry.getValue().pageNum));
        row.add(new Value((long) entry.getValue().slotNum));
        currentTuple = new TempTuple(row);
    }

    @Override
    public Tuple Current() { // Return Tuple
        return currentTuple;
    }

    @Override
    public void Close() {
        entries.clear();
        cursor = -1;
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }
}
