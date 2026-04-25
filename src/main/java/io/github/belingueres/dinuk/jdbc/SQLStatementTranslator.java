package io.github.belingueres.dinuk.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SQLStatementTranslator {

    /**
     * Placeholder embedded in the rewritten SQL for the physical table of a
     * deferred single-table statement, resolved at execution time.
     */
    public static final String DEFERRED_TABLE = "{deferred}";

    /**
     * Placeholder embedded in the rewritten SQL for the deferred partition key
     * value of a single-table statement, resolved at execution time.
     */
    public static final String DEFERRED_KEY = "{deferred_key}";

    /**
     * Placeholder for the {@code number}-th deferred table of a multi-table
     * statement, e.g. {@code {deferred1}}.
     *
     * @param number the 1-based index of the deferred table
     * @return the placeholder text for that table
     */
    public static String deferredTable(int number) {
        return "{deferred" + number + "}";
    }

    /**
     * Placeholder for the deferred partition key value of the {@code number}-th
     * table of a multi-table statement, e.g. {@code {deferred_key1}}.
     *
     * @param number the 1-based index of the deferred table
     * @return the placeholder text for that table's deferred key
     */
    public static String deferredKey(int number) {
        return "{deferred_key" + number + "}";
    }

    private int deferredPartitionKeyIndex = -1;
    private int removedColumnIndex = -1;
    private String rewrittenSql;
    private Map<Integer, Integer> deferredKeyToParamIndex = new HashMap<>();
    private Configuration.TableConfig singleTableConfig;
    private Map<Integer, Configuration.TableConfig> deferredKeyConfigs = new HashMap<>();

    // Move-mode: rewriting an UPDATE that changes the partition key into a
    // cross-partition move (INSERT into the new partition + DELETE from the old,
    // executed atomically). A single UPDATE cannot span two physical tables.
    private boolean move;
    private boolean moveKeepColumn;
    private Configuration.TableConfig moveConfig;
    private int moveNewKeyParamIndex = -1;
    private int moveOldKeyParamIndex = -1;
    private final List<MoveSetItem> moveSetItems = new ArrayList<>();

    /** A column updated by the caller's SET clause, and where its value comes from. */
    public static final class MoveSetItem {
        public final String column;
        public final int paramIndex;   // 1-based caller JDBC index, or -1 for a literal
        public final String literal;   // SQL literal when paramIndex == -1

        public MoveSetItem(String column, int paramIndex, String literal) {
            this.column = column;
            this.paramIndex = paramIndex;
            this.literal = literal;
        }
    }

    public void setMove(boolean move) {
        this.move = move;
    }

    public boolean isMove() {
        return move;
    }

    /**
     * Whether the partition key column is kept in the physical table (true for
     * value-mapped list/range/hash, false for key mode where it is encoded in
     * the table name).
     *
     * @param keepColumn whether the physical table retains the partition key column
     */
    public void setMoveKeepColumn(boolean keepColumn) {
        this.moveKeepColumn = keepColumn;
    }

    public boolean isMoveKeepColumn() {
        return moveKeepColumn;
    }

    public void setMoveConfig(Configuration.TableConfig config) {
        this.moveConfig = config;
    }

    public Configuration.TableConfig getMoveConfig() {
        return moveConfig;
    }

    /**
     * JDBC parameter index (in the caller's statement) of a deferred new
     * partition key value in the SET clause, or {@code -1} when it is a literal.
     *
     * @param index the 1-based caller parameter index, or {@code -1} for a literal
     */
    public void setMoveNewKeyParamIndex(int index) {
        this.moveNewKeyParamIndex = index;
    }

    public int getMoveNewKeyParamIndex() {
        return moveNewKeyParamIndex;
    }

    private String moveNewKeyLiteral;
    private String moveNewKeyRawLiteral;
    private String moveOldKeyRawLiteral;

    /**
     * SQL literal for a literal (non-bound) new partition key value.
     *
     * @param literal the SQL literal text to substitute for the new key value
     */
    public void setMoveNewKeyLiteral(String literal) {
        this.moveNewKeyLiteral = literal;
    }

    public String getMoveNewKeyLiteral() {
        return moveNewKeyLiteral;
    }

    /**
     * Raw (unquoted) text of a literal new partition key value, used to resolve
     * the target physical table when the value is not a bound parameter.
     *
     * @param raw the unquoted literal text of the new partition key value
     */
    public void setMoveNewKeyRawLiteral(String raw) {
        this.moveNewKeyRawLiteral = raw;
    }

    public String getMoveNewKeyRawLiteral() {
        return moveNewKeyRawLiteral;
    }

    /**
     * Raw (unquoted) text of a literal old partition key value, used to resolve
     * the source physical table when the value is not a bound parameter.
     *
     * @param raw the unquoted literal text of the old partition key value
     */
    public void setMoveOldKeyRawLiteral(String raw) {
        this.moveOldKeyRawLiteral = raw;
    }

    public String getMoveOldKeyRawLiteral() {
        return moveOldKeyRawLiteral;
    }

    /**
     * JDBC parameter index (in the caller's statement) of a deferred old
     * partition key value in the WHERE clause, or {@code -1} when a literal.
     *
     * @param index the 1-based caller parameter index, or {@code -1} for a literal
     */
    public void setMoveOldKeyParamIndex(int index) {
        this.moveOldKeyParamIndex = index;
    }

    public int getMoveOldKeyParamIndex() {
        return moveOldKeyParamIndex;
    }

    /**
     * Registers a non-key column set by the caller, and the source of its new
     * value ({@code paramIndex} >= 1 to re-bind, or {@code -1} with an inline
     * SQL {@code literal}).
     *
     * @param column the column name set by the caller's SET clause
     * @param paramIndex the 1-based caller parameter index to re-bind, or
     *         {@code -1} to use {@code literal} instead
     * @param literal the inline SQL literal to use when {@code paramIndex} is
     *         {@code -1}, otherwise ignored
     */
    public void addMoveSetColumn(String column, int paramIndex, String literal) {
        moveSetItems.add(new MoveSetItem(column, paramIndex, literal));
    }

    public List<MoveSetItem> getMoveSetItems() {
        return moveSetItems;
    }

    private String moveWhereText;

    public void setMoveWhereText(String sql) {
        this.moveWhereText = sql;
    }

    public String getMoveWhereText() {
        return moveWhereText;
    }

    public void setDeferredPartitionKeyIndex(int columnIndex) {
        this.deferredPartitionKeyIndex = columnIndex;
    }

    public int getDeferredPartitionKeyIndex() {
        return deferredPartitionKeyIndex;
    }

    public void setRemovedColumnIndex(int index) {
        this.removedColumnIndex = index;
    }

    public int getRemovedColumnIndex() {
        return removedColumnIndex;
    }

    public String getRewrittenSql() {
        return rewrittenSql;
    }

    public void setRewrittenSql(String rewrittenSql) {
        this.rewrittenSql = rewrittenSql;
    }

    public void addDeferredKey(int deferredNumber, int paramIndex) {
        deferredKeyToParamIndex.put(deferredNumber, paramIndex);
    }

    /**
     * Whether a deferred partition key is registered at the given JDBC
     * parameter index.
     *
     * @param paramIndex the 1-based caller parameter index to check
     * @return {@code true} if a deferred partition key is registered at that index
     */
    public boolean isDeferredParameter(int paramIndex) {
        return deferredKeyToParamIndex.containsValue(paramIndex);
    }

    /**
     * The lowest JDBC parameter index at which a deferred partition key is
     * registered, or {@code -1} when none is.
     *
     * @return the lowest such 1-based caller parameter index, or {@code -1}
     */
    public int getFirstDeferredParamIndex() {
        int first = -1;
        for (int paramIndex : deferredKeyToParamIndex.values()) {
            if (first == -1 || paramIndex < first) {
                first = paramIndex;
            }
        }
        return first;
    }

    /**
     * How many deferred partition key parameters are bound strictly before the
     * given JDBC parameter index. Deferred keys are substituted as literals in
     * the final SQL, so every subsequent parameter must shift down by one per
     * deferred key that precedes it. Covers both the single-table deferred key
     * ({@link #deferredPartitionKeyIndex}) and the multi-table deferred keys
     * ({@link #deferredKeyToParamIndex}).
     *
     * @param parameterIndex the 1-based caller parameter index to check against
     * @return the count of deferred partition key parameters bound before it
     */
    public int countDeferredParametersBefore(int parameterIndex) {
        int count = 0;
        if (deferredPartitionKeyIndex != -1 && deferredPartitionKeyIndex < parameterIndex) {
            count++;
        }
        for (int deferredParamIndex : deferredKeyToParamIndex.values()) {
            if (deferredParamIndex < parameterIndex && deferredParamIndex != deferredPartitionKeyIndex) {
                count++;
            }
        }
        return count;
    }

    public void setSingleTableConfig(Configuration.TableConfig config) {
        this.singleTableConfig = config;
    }

    public void setDeferredKeyConfig(int deferredNumber, Configuration.TableConfig config) {
        deferredKeyConfigs.put(deferredNumber, config);
    }

    @Override
    public String toString() {
        return "SQLStatementTranslator [deferredPartitionKeyIndex=" + deferredPartitionKeyIndex + ", rewrittenSql="
                + rewrittenSql + "]";
    }

    /**
     * Only letters, digits and underscores are allowed in a key-mode physical
     * table suffix, so a bound value can never alter the structure of the SQL.
     */
    private static final java.util.regex.Pattern SAFE_TABLE_SUFFIX =
            java.util.regex.Pattern.compile("\\w+");

    public static boolean isValidTableSuffix(String suffix) {
        return SAFE_TABLE_SUFFIX.matcher(suffix).matches();
    }

    /**
     * A table prefix (or configured table name) is either a plain identifier
     * or a schema-qualified name, so dots are permitted in addition to
     * {@code \w}.
     */
    private static final java.util.regex.Pattern SAFE_TABLE_PREFIX =
            java.util.regex.Pattern.compile("[\\w.]+");

    public static boolean isValidTablePrefix(String prefix) {
        return prefix != null && SAFE_TABLE_PREFIX.matcher(prefix).matches();
    }

    private static final java.util.regex.Pattern PLACEHOLDER_TOKEN =
            java.util.regex.Pattern.compile("\\{deferred(?:_key)?\\d*\\}");

    public static boolean containsPlaceholderToken(String sql) {
        return PLACEHOLDER_TOKEN.matcher(sql).find();
    }

    public String getFinalSql(Object keyValue) throws SQLException {
        if (deferredPartitionKeyIndex != -1) {
            Map<Integer, Object> deferredValues = new HashMap<>();
            deferredValues.put(deferredPartitionKeyIndex, keyValue);
            return getFinalSql(deferredValues);
        }
        return rewrittenSql;
    }

    public String getFinalSql(Map<Integer, Object> deferredValues) throws SQLException {
        String result = rewrittenSql;

        // Handle single-table deferred
        if (deferredPartitionKeyIndex != -1 && deferredKeyToParamIndex.isEmpty()) {
            Object value = deferredValues.get(deferredPartitionKeyIndex);
            if (value != null) {
                if (containsPlaceholderToken(String.valueOf(value))) {
                    throw new SQLException("Partition key value '" + value
                            + "' contains a reserved placeholder token and is not allowed.");
                }
                result = result.replace(DEFERRED_TABLE, resolveTable(singleTableConfig, value))
                              .replace(DEFERRED_KEY, PartitionKeyUtils.toSqlLiteral(value));
            } else if (result.contains(DEFERRED_TABLE)) {
                throw new SQLException("Partition key value is required but was not provided in runtime parameters.");
            }
        }

        // Handle multi-table deferred
        for (Map.Entry<Integer, Integer> entry : deferredKeyToParamIndex.entrySet()) {
            int number = entry.getKey();
            int paramIndex = entry.getValue();
            Object value = deferredValues.get(paramIndex);
            if (value == null) {
                throw new SQLException(
                        "Partition key value is required for deferred" + number + " but was not provided.");
            }
            if (containsPlaceholderToken(String.valueOf(value))) {
                throw new SQLException("Partition key value '" + value + "' for deferred" + number
                        + " contains a reserved placeholder token and is not allowed.");
            }
            result = result.replace(deferredTable(number), resolveTable(deferredKeyConfigs.get(number), value))
                          .replace(deferredKey(number), PartitionKeyUtils.toSqlLiteral(value));
        }

        return result;
    }

    /**
     * Resolves the physical table for a deferred partition key value. In
     * value-mapped modes (range, list or hash) the value must match a configured
     * mapping and the table name always comes from the configuration. In key
     * mode the value is used as the table suffix and must be a plain
     * identifier, so it cannot break out of the table name.
     */
    private String resolveTable(Configuration.TableConfig config, Object value) throws SQLException {
        if (config != null && config.isValueMapped()) {
            String table = config.getTableNameForValue(value.toString());
            if (table == null) {
                throw new SQLException("Partition key value '" + value + "' does not match any configured "
                        + config.getPartitionType() + " mapping for table '" + config.getViewNamePrefix() + "'.");
            }
            return table;
        }
        String suffix = value.toString().toLowerCase();
        if (!isValidTableSuffix(suffix)) {
            throw new SQLException("Partition key value '" + value + "' is not a valid table suffix for table '"
                    + (config != null ? config.getViewNamePrefix() : "") + "'.");
        }
        return suffix;
    }

}
