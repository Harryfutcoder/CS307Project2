package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;

import java.util.ArrayList;

public class SeqScanOperator implements PhysicalOperator {
    private final String tableName;
    private final DBManager dbManager;
    private final TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private Record currentRecord;
    private RID currentRid;

    private int currentPageNum;
    private int currentSlotNum;
    private int totalPages;
    private int recordsPerPage;
    private boolean isOpen = false;

    public SeqScanOperator(String tableName, DBManager dbManager) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            throw new IllegalArgumentException("Table does not exist: " + tableName, e);
        }
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) {
            return false;
        }
        return locateNextOccupiedSlot();
    }

    private boolean locateNextOccupiedSlot() throws DBException {
        while (currentPageNum < totalPages) {
            RecordPageHandle pageHandle = fileHandle.FetchPageHandle(currentPageNum);
            while (currentSlotNum < recordsPerPage) {
                if (BitMap.isSet(pageHandle.bitmap, currentSlotNum)) {
                    return true;
                }
                currentSlotNum++;
            }
            currentPageNum++;
            currentSlotNum = 0;
        }
        return false;
    }

    @Override
    public void Begin() throws DBException {
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        totalPages = Math.max(0, fileHandle.getFileHeader().getNumberOfPages() - 1);
        recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
        currentPageNum = 0;
        currentSlotNum = 0;
        currentRecord = null;
        currentRid = null;
        isOpen = true;
    }

    @Override
    public void Next() throws DBException {
        if (!isOpen) {
            currentRecord = null;
            currentRid = null;
            return;
        }
        if (!locateNextOccupiedSlot()) {
            currentRecord = null;
            currentRid = null;
            return;
        }
        RID rid = new RID(currentPageNum, currentSlotNum);
        currentRecord = fileHandle.GetRecord(rid);
        currentRid = rid;
        advanceCursor();
    }

    private void advanceCursor() {
        currentSlotNum++;
        if (currentSlotNum >= recordsPerPage) {
            currentPageNum++;
            currentSlotNum = 0;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null || currentRid == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, currentRid);
    }

    @Override
    public void Close() {
        if (!isOpen) {
            return;
        }
        try {
            if (fileHandle != null) {
                dbManager.getRecordManager().CloseFile(fileHandle);
            }
        } catch (DBException e) {
            // ignore close failure
        }
        fileHandle = null;
        currentRecord = null;
        currentRid = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }

    public RecordFileHandle getFileHandle() {
        return fileHandle;
    }

    public String getTableName() {
        return tableName;
    }

    public DBManager getDbManager() {
        return dbManager;
    }
}
