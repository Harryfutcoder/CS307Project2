package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayList;
import java.util.List;

public class ProjectOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private List<TabCol> outputSchema; // Use bounded wildcard
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<TabCol> outputSchema) { // Use bounded wildcard
        this.child = child;
        this.outputSchema = expandOutputSchema(outputSchema);
    }

    private List<TabCol> expandOutputSchema(List<TabCol> logicalOutputSchema) {
        List<TabCol> expanded = new ArrayList<>();
        for (TabCol tabCol : logicalOutputSchema) {
            if ("*".equals(tabCol.getTableName()) && "*".equals(tabCol.getColumnName())) {
                for (ColumnMeta columnMeta : child.outputSchema()) {
                    expanded.add(new TabCol(columnMeta.tableName, columnMeta.name));
                }
            } else if (!"*".equals(tabCol.getTableName()) && "*".equals(tabCol.getColumnName())) {
                for (ColumnMeta columnMeta : child.outputSchema()) {
                    if (columnMeta.tableName.equalsIgnoreCase(tabCol.getTableName())) {
                        expanded.add(new TabCol(columnMeta.tableName, columnMeta.name));
                    }
                }
            } else {
                expanded.add(tabCol);
            }
        }
        return expanded;
    }

    @Override
    public boolean hasNext() throws DBException {
        return child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
    }

    @Override
    public void Next() throws DBException {
        if (hasNext()) {
            child.Next();
            Tuple inputTuple = child.Current();
            if (inputTuple != null) {

                currentTuple = new ProjectTuple(inputTuple, outputSchema); // Create ProjectTuple
            } else {
                currentTuple = null;
            }
        } else {
            currentTuple = null;
        }
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int offset = 0;
        for (TabCol tabCol : outputSchema) {
            ColumnMeta matched = null;
            for (ColumnMeta childMeta : child.outputSchema()) {
                boolean columnMatches = childMeta.name.equalsIgnoreCase(tabCol.getColumnName());
                boolean tableMatches = tabCol.getTableName() == null
                        || tabCol.getTableName().isBlank()
                        || childMeta.tableName.equalsIgnoreCase(tabCol.getTableName());
                if (columnMatches && tableMatches) {
                    matched = childMeta;
                    break;
                }
            }
            if (matched != null) {
                schema.add(new ColumnMeta(matched.tableName, matched.name, matched.type, matched.len, offset));
                offset += matched.len;
            }
        }
        return schema;
    }
}
