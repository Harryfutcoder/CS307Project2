package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import org.pmw.tinylog.Logger;

import net.sf.jsqlparser.statement.ExplainStatement;

public class ExplainExecutor implements DMLExecutor {

    private final ExplainStatement explainStatement;
    private final DBManager dbManager;

    public ExplainExecutor(ExplainStatement explainStatement, DBManager dbManager) {
        this.explainStatement = explainStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        if (explainStatement.getStatement() == null) {
            throw new DBException(edu.sustech.cs307.exception.ExceptionTypes.InvalidSQL(
                    "EXPLAIN", "Missing query statement"));
        }
        String explainTargetSql = explainStatement.getStatement().toString();
        LogicalOperator operator = LogicalPlanner.resolveAndPlan(dbManager, explainTargetSql);
        if (operator == null) {
            Logger.info("No logical plan generated for: {}", explainTargetSql);
            return;
        }
        Logger.info(operator.toString());
    }
}
