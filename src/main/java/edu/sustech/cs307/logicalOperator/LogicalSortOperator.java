package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.statement.select.OrderByElement;

import java.util.Collections;
import java.util.List;

public class LogicalSortOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final List<OrderByElement> orderByElements;

    public LogicalSortOperator(LogicalOperator child, List<OrderByElement> orderByElements) {
        super(Collections.singletonList(child));
        this.child = child;
        this.orderByElements = orderByElements;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<OrderByElement> getOrderByElements() {
        return orderByElements;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "SortOperator(orderBy=" + orderByElements + ")";
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
