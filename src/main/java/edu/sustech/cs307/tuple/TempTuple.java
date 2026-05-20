package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.List;

public class TempTuple extends Tuple {
    private final List<Value> values;
    private final TabCol[] schema;

    public TempTuple(List<Value> values) {
        this.values = values;
        this.schema = null;
    }

    public TempTuple(List<Value> values, List<TabCol> schema) {
        this.values = values;
        this.schema = schema.toArray(new TabCol[0]);
    }

    @Override
    public Value getValue(TabCol tabCol) throws DBException {
        if (schema == null) {
            throw new DBException(ExceptionTypes.GetValueFromTempTuple());
        }
        for (int i = 0; i < schema.length; i++) {
            if (schema[i].getColumnName().equalsIgnoreCase(tabCol.getColumnName())) {
                if (tabCol.getTableName() == null || tabCol.getTableName().isBlank()
                        || schema[i].getTableName().equalsIgnoreCase(tabCol.getTableName())) {
                    return values.get(i);
                }
            }
        }
        return null;
    }

    @Override
    public TabCol[] getTupleSchema() {
        return schema;
    }

    @Override
    public Value[] getValues() throws DBException {
        return this.values.toArray(new Value[0]);
    }
}
