package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.SelectItem;

import java.util.Collections;
import java.util.List;

public class LogicalAggregateOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final GroupByElement groupByElement;

    public LogicalAggregateOperator(LogicalOperator child, List<SelectItem<?>> selectItems,
                                    GroupByElement groupByElement) {
        super(Collections.singletonList(child));
        this.child = child;
        this.selectItems = selectItems;
        this.groupByElement = groupByElement;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<SelectItem<?>> getSelectItems() {
        return selectItems;
    }

    public GroupByElement getGroupByElement() {
        return groupByElement;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "AggregateOperator(selectItems=" + selectItems + ", groupBy=" + groupByElement + ")";
        String[] childLines = child.toString().split("\\R");
        sb.append(nodeHeader);
        if (childLines.length > 0) {
            sb.append("\n└── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }
}
