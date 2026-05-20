package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;

import java.util.ArrayList;
import java.util.List;

public class IndexScanOperator implements PhysicalOperator {
    private final String tableName;
    private final DBManager dbManager;
    private final List<RID> rids;
    private final TableMeta tableMeta;

    private RecordFileHandle fileHandle;
    private int cursor = -1;
    private Tuple currentTuple;

    public IndexScanOperator(String tableName, DBManager dbManager, List<RID> rids) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        this.rids = rids;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            throw new IllegalArgumentException("Table does not exist: " + tableName, e);
        }
    }

    @Override
    public boolean hasNext() {
        return cursor + 1 < rids.size();
    }

    @Override
    public void Begin() throws DBException {
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        cursor = -1;
        currentTuple = null;
    }

    @Override
    public void Next() throws DBException {
        cursor++;
        if (cursor < 0 || cursor >= rids.size()) {
            currentTuple = null;
            return;
        }
        RID rid = rids.get(cursor);
        Record record = fileHandle.GetRecord(rid);
        currentTuple = new TableTuple(tableName, tableMeta, record, rid);
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        try {
            if (fileHandle != null) {
                dbManager.getRecordManager().CloseFile(fileHandle);
            }
        } catch (DBException ignored) {
        }
        currentTuple = null;
        cursor = -1;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }
}
