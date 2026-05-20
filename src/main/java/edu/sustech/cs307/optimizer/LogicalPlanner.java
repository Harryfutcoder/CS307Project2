package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.expression.Function;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern DESC_TABLE_PATTERN =
            Pattern.compile("(?i)^(?:DESC|DESCRIBE)\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?i)^DROP\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualMetaCommand(dbManager, sql)) {
            return null;
        }
        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement, dbManager);
            showDatabaseExecutor.execute();
            return null;
        } else if (stmt instanceof CreateIndex createIndexStmt) {
            handleCreateIndex(dbManager, createIndexStmt);
            return null;
        } else if (stmt instanceof Drop dropStmt) {
            handleDropStatement(dbManager, dropStmt);
            return null;
        } else if (stmt instanceof Alter alterStmt) {
            handleAlterStatement(dbManager, alterStmt);
            return null;
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
        }
        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        boolean hasAggregateFunction = false;
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            if (selectItem.getExpression() instanceof Function) {
                hasAggregateFunction = true;
                break;
            }
        }

        if (hasAggregateFunction || plainSelect.getGroupBy() != null) {
            root = new LogicalAggregateOperator(root, plainSelect.getSelectItems(), plainSelect.getGroupBy());
        } else {
            root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        }
        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalSortOperator(root, plainSelect.getOrderByElements());
        }
        return root;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        String tableName = deleteStmt.getTable().getName();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static void handleCreateIndex(DBManager dbManager, CreateIndex createIndexStmt) throws DBException {
        String indexName = createIndexStmt.getIndex().getName();
        String tableName = createIndexStmt.getTable().getName();
        if (createIndexStmt.getIndex().getColumnsNames() == null || createIndexStmt.getIndex().getColumnsNames().isEmpty()) {
            throw new DBException(ExceptionTypes.InvalidSQL("CREATE INDEX", "Missing indexed column"));
        }
        String columnName = createIndexStmt.getIndex().getColumnsNames().get(0);
        dbManager.createIndex(indexName, tableName, columnName);
    }

    private static void handleDropStatement(DBManager dbManager, Drop dropStmt) throws DBException {
        String type = dropStmt.getType();
        if (type != null && type.equalsIgnoreCase("INDEX")) {
            dbManager.dropIndex(dropStmt.getName().getName());
            return;
        }
        if (type != null && type.equalsIgnoreCase("TABLE")) {
            dbManager.dropTable(dropStmt.getName().getName());
            return;
        }
        throw new DBException(ExceptionTypes.UnsupportedCommand(dropStmt.toString()));
    }

    private static void handleAlterStatement(DBManager dbManager, Alter alterStmt) throws DBException {
        String tableName = alterStmt.getTable().getName();
        for (AlterExpression expression : alterStmt.getAlterExpressions()) {
            if (expression.getOperation() == AlterOperation.ADD && expression.getColDataTypeList() != null
                    && !expression.getColDataTypeList().isEmpty()) {
                var columnDef = expression.getColDataTypeList().get(0);
                String columnName = columnDef.getColumnName();
                String dataType = columnDef.getColDataType().getDataType();
                dbManager.alterTableAddColumn(tableName, columnName, dataType);
            } else if (expression.getOperation() == AlterOperation.DROP && expression.getColumnName() != null) {
                dbManager.alterTableDropColumn(tableName, expression.getColumnName());
            } else {
                throw new DBException(ExceptionTypes.UnsupportedCommand(alterStmt.toString()));
            }
        }
    }
    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            dbManager.getTransactionManager().savepoint(savepointMatcher.group(1));
            return true;
        }
        Matcher rollbackToMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToMatcher.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(rollbackToMatcher.group(1));
            return true;
        }
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        Matcher releaseMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseMatcher.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(releaseMatcher.group(1));
            return true;
        }
        return false;
    }

    private static boolean handleManualMetaCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.showTables();
            return true;
        }
        Matcher descMatcher = DESC_TABLE_PATTERN.matcher(normalizedSql);
        if (descMatcher.matches()) {
            dbManager.descTable(descMatcher.group(1));
            return true;
        }
        Matcher dropMatcher = DROP_TABLE_PATTERN.matcher(normalizedSql);
        if (dropMatcher.matches()) {
            dbManager.dropTable(dropMatcher.group(1));
            return true;
        }
        return false;
    }


}
