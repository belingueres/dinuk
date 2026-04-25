package io.github.belingueres.dinuk.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes an UPDATE that changes the partition key as an atomic cross-partition
 * move. A single SQL statement cannot span two physical tables, so the driver
 * copies the row into the new partition (INSERT ... SELECT, applying the SET
 * columns) and deletes the original, under one transaction on the same
 * connection.
 *
 * <p>If the new and old key values resolve to the same physical table the move
 * degrades to the ordinary in-place UPDATE.
 */
public final class MoveExecutor {

    private static final Logger log = LoggerFactory.getLogger(MoveExecutor.class);

    private static final Pattern WIDX = Pattern.compile("\\{widx:(\\d+)\\}");

    private MoveExecutor() {
    }

    /**
     * Runs the move for a statement whose parameters are available.
     *
     * @param delegate  the real connection (transaction + metadata)
     * @param config    the partition config for the view
     * @param translator the move plan
     * @param callerParams caller parameter index -&gt; bound value, keyed 1-based
     * @return the number of rows moved (what the original UPDATE would report)
     * @throws SQLException if the generated INSERT/DELETE (or in-place UPDATE)
     *         fails to execute
     */
    public static int execute(Connection delegate, Configuration.TableConfig config,
            SQLStatementTranslator translator, Map<Integer, Object> callerParams) throws SQLException {
        Object newValue = translator.getMoveNewKeyParamIndex() >= 0
                ? valueAt(callerParams, translator.getMoveNewKeyParamIndex())
                : translator.getMoveNewKeyRawLiteral();
        Object oldValue = translator.getMoveOldKeyParamIndex() >= 0
                ? valueAt(callerParams, translator.getMoveOldKeyParamIndex())
                : translator.getMoveOldKeyRawLiteral();
        String targetTable = resolveTable(config, newValue);
        String sourceTable = resolveTable(config, oldValue);
        if (targetTable == null || sourceTable == null) {
            throw new SQLException("Partition key value does not map to any configured physical table for '"
                    + config.getViewNamePrefix() + "': new=" + newValue + ", old=" + oldValue + ".");
        }
        if (targetTable.equalsIgnoreCase(sourceTable)) {
            return executeInPlace(delegate, translator, callerParams, oldValue, targetTable);
        }

        boolean keepColumn = translator.isMoveKeepColumn();
        List<Column> columns = physicalColumns(delegate, sourceTable);

        StringBuilder insertCols = new StringBuilder();
        StringBuilder selectExprs = new StringBuilder();
        List<Integer> selectParamSources = new ArrayList<>();
        buildSelect(translator, config, columns, keepColumn, insertCols, selectExprs, selectParamSources);

        List<Integer> whereParamSources = new ArrayList<>();
        String resolvedWhere = resolveWhere(translator.getMoveWhereText(), translator.getMoveOldKeyParamIndex(),
                oldValue, whereParamSources);
        String where = resolvedWhere.isEmpty() ? "" : " WHERE " + resolvedWhere;

        String sourceSql = "INSERT INTO " + targetTable + " (" + insertCols + ") SELECT " + selectExprs
                + " FROM " + sourceTable + where;
        String deleteSql = "DELETE FROM " + sourceTable + where;

        List<Integer> sourceParamSources = new ArrayList<>(selectParamSources);
        sourceParamSources.addAll(whereParamSources);

        boolean origAutoCommit = delegate.getAutoCommit();
        try {
            if (origAutoCommit) {
                delegate.setAutoCommit(false);
            }
            int moved = 0;
            log.trace("connection hash: {}, executing SQL: {}", delegate.hashCode(), sourceSql);
            try (PreparedStatement sourcePs = delegate.prepareStatement(sourceSql)) {
                bind(sourcePs, callerParams, sourceParamSources);
                moved = sourcePs.executeUpdate();
            }
            if (moved > 0) {
                log.trace("connection hash: {}, executing SQL: {}", delegate.hashCode(), deleteSql);
                try (PreparedStatement deletePs = delegate.prepareStatement(deleteSql)) {
                    bind(deletePs, callerParams, whereParamSources);
                    deletePs.executeUpdate();
                }
            }
            if (origAutoCommit) {
                delegate.commit();
            }
            log.debug("Move executed: '{}' -> '{}' moved {} row(s)", sourceTable, targetTable, moved);
            return moved;
        } catch (SQLException e) {
            if (origAutoCommit) {
                rollbackQuietly(delegate);
            }
            throw e;
        } finally {
            if (origAutoCommit) {
                setAutoCommitQuietly(delegate, true);
            }
        }
    }

    /**
     * Replaces the {@code {widx:NN}} tokens in the move WHERE with either the old
     * key as a literal (the old key index) or a statement {@code ?}, recording the
     * ordered caller param sources for the latter.
     */
    private static String resolveWhere(String tokenWhere, int oldKeyCallerIndex, Object oldValue,
            List<Integer> whereParamSources) {
        if (tokenWhere == null || tokenWhere.isEmpty()) {
            return "";
        }
        StringBuffer out = new StringBuffer();
        Matcher m = WIDX.matcher(tokenWhere);
        while (m.find()) {
            int callerIdx = Integer.parseInt(m.group(1));
            if (callerIdx == oldKeyCallerIndex) {
                m.appendReplacement(out, Matcher.quoteReplacement(
                        oldValue == null ? "NULL" : PartitionKeyUtils.toSqlLiteral(oldValue)));
            } else {
                whereParamSources.add(callerIdx);
                m.appendReplacement(out, "?");
            }
        }
        m.appendTail(out);
        return out.toString();
    }
    private static void buildSelect(SQLStatementTranslator translator, Configuration.TableConfig config,
            List<Column> columns, boolean keepColumn, StringBuilder insertCols, StringBuilder selectExprs,
            List<Integer> insertParamSources) {
        List<String> names = new ArrayList<>();
        for (Column column : columns) {
            names.add(column.name);
        }
        // For value-mapped (list/range/hash) tables the partition key column is
        // already part of the physical table; key mode drops it. Only add it when
        // the metadata did not already include it.
        boolean keyPresent = false;
        for (String n : names) {
            if (columnNameEquals(n, config.getPartitionKey())) {
                keyPresent = true;
                break;
            }
        }
        if (keepColumn && !keyPresent) {
            names.add(config.getPartitionKey());
        }
        for (String name : names) {
            if (insertCols.length() > 0) {
                insertCols.append(", ");
                selectExprs.append(", ");
            }
            insertCols.append(name);
            int src = paramSourceFor(translator, config, name);
            selectExprs.append(src > 0 ? "?" : expressionFor(translator, config, name));
            if (src > 0) {
                insertParamSources.add(src);
            }
        }
    }

    private static int paramSourceFor(SQLStatementTranslator translator, Configuration.TableConfig config,
            String column) {
        if (columnNameEquals(column, config.getPartitionKey())) {
            return translator.getMoveNewKeyParamIndex();
        }
        for (SQLStatementTranslator.MoveSetItem item : translator.getMoveSetItems()) {
            if (column.equalsIgnoreCase(item.column)) {
                return item.paramIndex;
            }
        }
        return 0;
    }

    private static String expressionFor(SQLStatementTranslator translator, Configuration.TableConfig config,
            String column) {
        if (columnNameEquals(column, config.getPartitionKey())) {
            String lit = translator.getMoveNewKeyLiteral();
            return lit == null ? "NULL" : lit;
        }
        for (SQLStatementTranslator.MoveSetItem item : translator.getMoveSetItems()) {
            if (column.equalsIgnoreCase(item.column)) {
                return item.literal;
            }
        }
        return column;
    }

    private static void bind(PreparedStatement ps, Map<Integer, Object> callerParams, List<Integer> sources)
            throws SQLException {
        for (int i = 0; i < sources.size(); i++) {
            ps.setObject(i + 1, callerParams.get(sources.get(i)));
        }
    }

    /**
     * Same physical table: build a plain UPDATE against it directly, reusing
     * the same SET/WHERE assembly as the cross-partition move, rather than
     * reusing the caller's original SQL text. The original text cannot be
     * reused as-is for key mode: it still assigns and filters on the
     * partition-key column, which the physical table does not have.
     */
    private static int executeInPlace(Connection delegate, SQLStatementTranslator translator,
            Map<Integer, Object> callerParams, Object oldValue, String table) throws SQLException {
        Configuration.TableConfig config = translator.getMoveConfig();
        StringBuilder setClause = new StringBuilder();
        List<Integer> setParamSources = new ArrayList<>();
        if (translator.isMoveKeepColumn()) {
            appendSetItem(setClause, setParamSources, config.getPartitionKey(),
                    translator.getMoveNewKeyParamIndex(), translator.getMoveNewKeyLiteral());
        }
        for (SQLStatementTranslator.MoveSetItem item : translator.getMoveSetItems()) {
            appendSetItem(setClause, setParamSources, item.column, item.paramIndex, item.literal);
        }
        if (setClause.length() == 0) {
            throw new SQLException("Move produced no SET columns for an in-place update on '" + table
                    + "'; the UPDATE must assign at least one column besides an unchanged key-mode partition key.");
        }

        List<Integer> whereParamSources = new ArrayList<>();
        String resolvedWhere = resolveWhere(translator.getMoveWhereText(), translator.getMoveOldKeyParamIndex(),
                oldValue, whereParamSources);
        String where = resolvedWhere.isEmpty() ? "" : " WHERE " + resolvedWhere;

        String sql = "UPDATE " + table + " SET " + setClause + where;
        log.trace("connection hash: {}, executing SQL: {}", delegate.hashCode(), sql);
        try (PreparedStatement ps = delegate.prepareStatement(sql)) {
            List<Integer> sources = new ArrayList<>(setParamSources);
            sources.addAll(whereParamSources);
            bind(ps, callerParams, sources);
            return ps.executeUpdate();
        }
    }

    private static void appendSetItem(StringBuilder sql, List<Integer> paramSources, String column,
            int paramIndex, String literal) {
        if (sql.length() > 0) {
            sql.append(", ");
        }
        sql.append(column).append("=");
        if (paramIndex > 0) {
            sql.append("?");
            paramSources.add(paramIndex);
        } else {
            sql.append(literal == null ? "NULL" : literal);
        }
    }

    private static Object valueAt(Map<Integer, Object> callerParams, int index) {
        return index < 0 ? null : callerParams.get(index);
    }

    /**
     * Resolves the physical table for a partition key value, exactly like
     * {@link SQLStatementTranslator}'s equivalent: value-mapped modes (range,
     * list, hash) only ever return a table name that was explicitly
     * configured. In key mode the value itself becomes the table suffix, so it
     * must be validated as a plain identifier before splicing it into the
     * move's generated SQL — otherwise a caller-supplied value (bound
     * parameter or literal) could break out of the table-name position and
     * inject arbitrary SQL.
     */
    private static String resolveTable(Configuration.TableConfig config, Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (config != null && config.isValueMapped()) {
            return config.getTableNameForValue(value.toString());
        }
        String suffix = value.toString().toLowerCase();
        if (!SQLStatementTranslator.isValidTableSuffix(suffix)) {
            throw new SQLException("Partition key value '" + value + "' is not a valid table suffix for table '"
                    + config.getViewNamePrefix() + "'.");
        }
        return config.getViewNamePrefix() + "_" + suffix;
    }

    private static List<Column> physicalColumns(Connection delegate, String table) throws SQLException {
        DatabaseMetaData meta = delegate.getMetaData();
        String schema = table.contains(".") ? table.split("\\.")[0] : null;
        String name = table.contains(".") ? table.split("\\.")[1] : table;
        List<Column> columns = new ArrayList<>();
        for (String candidateName : new String[] {name, name.toUpperCase(), name.toLowerCase()}) {
            try (ResultSet rs = meta.getColumns(null, schema, candidateName, null)) {
                rs.beforeFirst();
                int count = 0;
                while (rs.next()) {
                    count++;
                    columns.add(new Column(rs.getString("COLUMN_NAME"), rs.getInt("ORDINAL_POSITION")));
                }
                if (count > 0) {
                    break;
                }
                columns.clear();
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("No columns resolved for physical table '" + table
                    + "' via DatabaseMetaData; cannot build the partition move.");
        }
        columns.sort((a, b) -> Integer.compare(a.ordinal, b.ordinal));
        return columns;
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // preserve original failure
        }
    }

    private static void setAutoCommitQuietly(Connection c, boolean value) {
        try {
            c.setAutoCommit(value);
        } catch (SQLException ignored) {
            // preserve original outcome
        }
    }

    private static boolean columnNameEquals(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static final class Column {
        final String name;
        final int ordinal;

        Column(String name, int ordinal) {
            this.name = name;
            this.ordinal = ordinal;
        }
    }
}
