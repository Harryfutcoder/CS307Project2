package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.index.InMemoryOrderedIndex;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.physicalOperator.SeqScanOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import edu.sustech.cs307.tuple.TableTuple;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    public void showTables() {
        List<String> tableNames = metaManager.getTableNames().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        Logger.info("|-----------|");
        Logger.info("| Tables    |");
        Logger.info("|-----------|");
        for (String tableName : tableNames) {
            Logger.info(String.format("| %s |", StringUtils.center(tableName, 9, ' ')));
        }
        Logger.info("|-----------|");
    }

    public void descTable(String table_name) throws DBException {
        TableMeta tableMeta = metaManager.getTable(table_name);
        Logger.info("|-------------------------|");
        Logger.info("| Field        | Type     |");
        Logger.info("|-------------------------|");
        for (ColumnMeta column : tableMeta.columns_list) {
            String field = StringUtils.center(column.name, 12, ' ');
            String type = StringUtils.center(column.type.toString().toLowerCase(), 8, ' ');
            Logger.info(String.format("| %s | %s |", field, type));
        }
        Logger.info("|-------------------------|");
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
    public void dropTable(String table_name) throws DBException {
        if (!isTableExists(table_name)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(table_name));
        }
        TableMeta tableMeta = metaManager.getTable(table_name);
        for (String indexName : new ArrayList<>(tableMeta.getIndexes().keySet())) {
            deleteIndexFile(table_name, indexName);
        }
        String dataFile = String.format("%s/%s", table_name, "data");
        bufferPool.DeleteAllPages(dataFile);
        recordManager.DeleteFile(dataFile);
        File tableFolder = new File(diskManager.getCurrentDir(), table_name);
        if (tableFolder.exists()) {
            deleteDirectory(tableFolder);
        }
        metaManager.dropTable(table_name);
        Logger.info("Successfully dropped table: {}", table_name);
    }

    public void createIndex(String indexName, String tableName, String columnName) throws DBException {
        if (!isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (tableMeta.getIndexes().containsKey(indexName)) {
            throw new DBException(ExceptionTypes.IndexAlreadyExist(indexName));
        }
        if (tableMeta.getColumnMeta(columnName) == null) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(columnName));
        }

        metaManager.addIndexInTable(tableName, indexName, columnName, TableMeta.IndexType.BTREE);
        rebuildSingleIndex(tableName, indexName, columnName);
        Logger.info("Successfully created index: {} on {}({})", indexName, tableName, columnName);
    }

    public void dropIndex(String indexName) throws DBException {
        String ownerTable = null;
        for (String tableName : metaManager.getTableNames()) {
            TableMeta tableMeta = metaManager.getTable(tableName);
            if (tableMeta.getIndexes().containsKey(indexName)) {
                ownerTable = tableName;
                break;
            }
        }
        if (ownerTable == null) {
            throw new DBException(ExceptionTypes.IndexDoesNotExist(indexName));
        }
        metaManager.dropIndexInTable(ownerTable, indexName);
        deleteIndexFile(ownerTable, indexName);
        Logger.info("Successfully dropped index: {}", indexName);
    }

    public void refreshIndexesForTable(String tableName) throws DBException {
        if (!isTableExists(tableName)) {
            return;
        }
        TableMeta tableMeta = metaManager.getTable(tableName);
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            rebuildSingleIndex(tableName, entry.getKey(), entry.getValue());
        }
    }

    public InMemoryOrderedIndex loadIndex(String tableName, String indexName) {
        return new InMemoryOrderedIndex(getIndexFilePath(tableName, indexName));
    }

    public String findIndexOnColumn(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(columnName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void alterTableAddColumn(String tableName, String columnName, String dataType) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (hasAnyRecord(tableName)) {
            throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE",
                    "Only empty tables are supported for ALTER ADD COLUMN"));
        }
        ColumnMeta columnMeta = new ColumnMeta(tableName, columnName, parseValueType(dataType), 0, 0);
        columnMeta.len = switch (columnMeta.type) {
            case INTEGER -> edu.sustech.cs307.value.Value.INT_SIZE;
            case FLOAT -> edu.sustech.cs307.value.Value.FLOAT_SIZE;
            case CHAR -> edu.sustech.cs307.value.Value.CHAR_SIZE;
            default -> throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE",
                    "Unsupported column type: " + columnMeta.type));
        };
        metaManager.addColumnInTable(tableName, columnMeta);
        normalizeColumnOffsets(tableMeta);
        metaManager.saveToJson();
        recreateDataFile(tableName, tableMeta.columns_list);
        Logger.info("Successfully altered table {} add column {}", tableName, columnName);
    }

    public void alterTableDropColumn(String tableName, String columnName) throws DBException {
        TableMeta tableMeta = metaManager.getTable(tableName);
        if (hasAnyRecord(tableName)) {
            throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE",
                    "Only empty tables are supported for ALTER DROP COLUMN"));
        }
        metaManager.dropColumnInTable(tableName, columnName);
        normalizeColumnOffsets(tableMeta);
        // remove indexes bound to dropped column
        List<String> toDrop = new ArrayList<>();
        for (Map.Entry<String, String> entry : tableMeta.getIndexColumns().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(columnName)) {
                toDrop.add(entry.getKey());
            }
        }
        for (String indexName : toDrop) {
            tableMeta.getIndexes().remove(indexName);
            tableMeta.getIndexColumns().remove(indexName);
            deleteIndexFile(tableName, indexName);
        }
        metaManager.saveToJson();
        recreateDataFile(tableName, tableMeta.columns_list);
        Logger.info("Successfully altered table {} drop column {}", tableName, columnName);
    }

    private void rebuildSingleIndex(String tableName, String indexName, String columnName) throws DBException {
        InMemoryOrderedIndex index = new InMemoryOrderedIndex(getIndexFilePath(tableName, indexName));
        index.clear();
        SeqScanOperator scanner = new SeqScanOperator(tableName, this);
        scanner.Begin();
        while (scanner.hasNext()) {
            scanner.Next();
            TableTuple tuple = (TableTuple) scanner.Current();
            if (tuple == null) {
                continue;
            }
            var value = tuple.getValue(new TabCol(tableName, columnName));
            if (value != null) {
                index.put(value, tuple.getRID());
            }
        }
        scanner.Close();
        index.persist();
    }

    private void deleteIndexFile(String tableName, String indexName) {
        File file = new File(getIndexFilePath(tableName, indexName));
        if (file.exists()) {
            file.delete();
        }
    }

    private String getIndexFilePath(String tableName, String indexName) {
        return String.format("%s/%s/%s.idx.json", diskManager.getCurrentDir(), tableName, indexName);
    }

    private void recreateDataFile(String tableName, List<ColumnMeta> columns) throws DBException {
        String dataFile = String.format("%s/%s", tableName, "data");
        bufferPool.DeleteAllPages(dataFile);
        recordManager.DeleteFile(dataFile);
        int recordSize = 0;
        for (ColumnMeta columnMeta : columns) {
            recordSize += columnMeta.len;
        }
        recordManager.CreateFile(dataFile, recordSize);
    }

    private void normalizeColumnOffsets(TableMeta tableMeta) {
        int offset = 0;
        for (ColumnMeta columnMeta : tableMeta.columns_list) {
            columnMeta.offset = offset;
            offset += columnMeta.len;
        }
    }

    private boolean hasAnyRecord(String tableName) throws DBException {
        SeqScanOperator scanOperator = new SeqScanOperator(tableName, this);
        scanOperator.Begin();
        boolean hasRecord = scanOperator.hasNext();
        scanOperator.Close();
        return hasRecord;
    }

    private edu.sustech.cs307.value.ValueType parseValueType(String rawType) throws DBException {
        if (rawType == null) {
            throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE", "Missing column type"));
        }
        if (rawType.equalsIgnoreCase("int") || rawType.equalsIgnoreCase("integer") || rawType.equalsIgnoreCase("bigint")) {
            return edu.sustech.cs307.value.ValueType.INTEGER;
        }
        if (rawType.equalsIgnoreCase("float") || rawType.equalsIgnoreCase("double") || rawType.equalsIgnoreCase("decimal")) {
            return edu.sustech.cs307.value.ValueType.FLOAT;
        }
        if (rawType.equalsIgnoreCase("char") || rawType.equalsIgnoreCase("varchar") || rawType.equalsIgnoreCase("string")
                || rawType.equalsIgnoreCase("text")) {
            return edu.sustech.cs307.value.ValueType.CHAR;
        }
        throw new DBException(ExceptionTypes.InvalidSQL("ALTER TABLE", "Unsupported column type: " + rawType));
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }
}
