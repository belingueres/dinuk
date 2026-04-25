package io.github.belingueres.dinuk.jdbc;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;

final class PartitionKeyUtils {

    private PartitionKeyUtils() {
    }

    static boolean isParameter(SQLExpr expr) {
        return expr instanceof SQLVariantRefExpr && ((SQLVariantRefExpr) expr).getName().equals("?");
    }

    static boolean isPartitionKeyEquality(SQLExpr expression, String tableAlias, String partitionKey) {
        if (!(expression instanceof SQLBinaryOpExpr)) {
            return false;
        }
        SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expression;
        if (binary.getOperator() != SQLBinaryOperator.Equality) {
            return false;
        }
        if (!isPartitionKeyReference(binary.getLeft(), tableAlias, partitionKey)) {
            return false;
        }
        // Ensure the right side is a value, not a column reference (e.g., o.id = u.id is a join condition)
        SQLExpr right = binary.getRight();
        return !(right instanceof SQLIdentifierExpr || right instanceof SQLPropertyExpr);
    }

    static boolean isPartitionKeyReference(SQLExpr expression, String tableAlias, String partitionKey) {
        if (expression instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) expression).nameEquals(partitionKey);
        }

        if (expression instanceof SQLPropertyExpr) {
            SQLPropertyExpr property = (SQLPropertyExpr) expression;
            if (!property.nameEquals(partitionKey)) {
                return false;
            }
            if (tableAlias == null || tableAlias.isEmpty()) {
                return true;
            }
            String ownerName = property.getOwnerName();
            return ownerName != null && ownerName.equalsIgnoreCase(tableAlias);
        }

        return false;
    }

    static boolean containsOrOnPartitionKey(SQLExpr where, String tableAlias, String partitionKey) {
        if (!(where instanceof SQLBinaryOpExpr)) {
            return false;
        }

        SQLBinaryOpExpr binary = (SQLBinaryOpExpr) where;
        if (binary.getOperator() == SQLBinaryOperator.BooleanOr) {
            return containsPartitionKeyReference(binary, tableAlias, partitionKey);
        }

        return containsOrOnPartitionKey(binary.getLeft(), tableAlias, partitionKey)
                || containsOrOnPartitionKey(binary.getRight(), tableAlias, partitionKey);
    }

    static boolean containsPartitionKeyReference(SQLExpr expression, String tableAlias, String partitionKey) {
        if (isPartitionKeyReference(expression, tableAlias, partitionKey)) {
            return true;
        }
        if (expression instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expression;
            return containsPartitionKeyReference(binary.getLeft(), tableAlias, partitionKey)
                    || containsPartitionKeyReference(binary.getRight(), tableAlias, partitionKey);
        }
        return false;
    }

    /**
     * Renders a partition key value as a safe SQL literal: standard library
     * numbers and booleans are emitted verbatim, anything else is quoted as a
     * string literal with single quotes doubled, so a bound value can never
     * inject SQL. A user-supplied {@code Number} subclass is quoted, since its
     * {@code toString()} may not be a plain numeric literal. Must not be called
     * with a {@code null} value.
     */
    static String toSqlLiteral(Object value) {
        if (value instanceof Boolean || isStandardLibraryNumber(value)) {
            return value.toString();
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /**
     * Whether the value is a {@code Number} whose class is loaded by the
     * bootstrap class loader, i.e. a standard library number type. Subclasses
     * defined by the application are not trusted with an unquoted splice.
     */
    private static boolean isStandardLibraryNumber(Object value) {
        return value instanceof Number && value.getClass().getClassLoader() == null;
    }
}
