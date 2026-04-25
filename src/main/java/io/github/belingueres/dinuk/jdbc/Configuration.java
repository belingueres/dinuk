package io.github.belingueres.dinuk.jdbc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.DbType;

public class Configuration {

    public static final int DEFAULT_MAXIMUM_CACHE_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(Configuration.class);

    public static class TableConfig {
        private final String viewNamePrefix;
        private final String partitionType;
        private final String partitionKey;
        private final List<RangeEntry> ranges = new ArrayList<>();
        private final List<ListEntry> lists = new ArrayList<>();
        private int hashPartitions;

        public TableConfig(String viewNamePrefix, String partitionType, String partitionKey) {
            String prefix = viewNamePrefix == null ? null : viewNamePrefix.trim();
            if (!SQLStatementTranslator.isValidTablePrefix(prefix)) {
                throw new IllegalArgumentException("Invalid viewNamePrefix '" + viewNamePrefix
                        + "'; must be a plain identifier or a schema-qualified name");
            }
            String key = partitionKey == null ? null : partitionKey.trim();
            if (key == null || !SQLStatementTranslator.isValidTableSuffix(key)) {
                throw new IllegalArgumentException("Invalid partitionKey '" + partitionKey
                        + "'; must be a non-null plain identifier");
            }
            this.viewNamePrefix = prefix;
            this.partitionType = partitionType;
            this.partitionKey = key;
        }

        public String getViewNamePrefix() { return viewNamePrefix; }
        public String getPartitionType() { return partitionType; }
        public String getPartitionKey() { return partitionKey; }

        public boolean isRangeType() {
            return partitionType != null && partitionType.trim().equalsIgnoreCase("range");
        }

        public boolean isListType() {
            return partitionType != null && partitionType.trim().equalsIgnoreCase("list");
        }

        public boolean isHashType() {
            return partitionType != null && partitionType.trim().equalsIgnoreCase("hash");
        }

        /**
         * Whether the physical table for a value is derived from the configured
         * mappings (range, list or hash) rather than encoded in the table name
         * suffix (key mode).
         *
         * @return {@code true} if this table is range-, list- or hash-partitioned
         */
        public boolean isValueMapped() {
            return isRangeType() || isListType() || isHashType();
        }

        public void setHashPartitions(int hashPartitions) {
            this.hashPartitions = hashPartitions;
        }

        public int getHashPartitions() {
            return hashPartitions;
        }

        public void addRange(RangeEntry range) {
            ranges.add(range);
        }

        public List<RangeEntry> getRanges() {
            return Collections.unmodifiableList(ranges);
        }

        public void addList(ListEntry list) {
            lists.add(list);
        }

        public List<ListEntry> getLists() {
            return Collections.unmodifiableList(lists);
        }

        /**
         * Resolves the physical table name for a partition key value in range
         * mode: the first declared range whose [low, high] bounds contain the
         * value wins. Returns {@code null} when the value matches no range.
         *
         * @param value the partition key value to resolve
         * @return the matching physical table name, or {@code null} if none matches
         */
        public String getRangeTableName(String value) {
            if (!isRangeType()) {
                return null;
            }
            for (RangeEntry range : ranges) {
                if (range.matches(value)) {
                    return range.getTableName();
                }
            }
            return null;
        }

        /**
         * Resolves the physical table name for a partition key value in list
         * mode: the first declared list whose discrete values contain the value
         * wins. Returns {@code null} when the value matches no list.
         *
         * @param value the partition key value to resolve
         * @return the matching physical table name, or {@code null} if none matches
         */
        public String getListTableName(String value) {
            if (!isListType()) {
                return null;
            }
            for (ListEntry list : lists) {
                if (list.contains(value)) {
                    return list.getTableName();
                }
            }
            return null;
        }

        /**
         * Resolves the physical table name for a partition key value in any
         * value-mapped mode (range, list or hash). Returns {@code null} when the
         * value matches no configured mapping.
         *
         * @param value the partition key value to resolve
         * @return the matching physical table name, or {@code null} if none matches
         */
        public String getTableNameForValue(String value) {
            if (isRangeType()) {
                return getRangeTableName(value);
            }
            if (isListType()) {
                return getListTableName(value);
            }
            if (isHashType()) {
                return getHashTableName(value);
            }
            return null;
        }

        /**
         * Resolves the physical table name for a partition key value in hash
         * mode: the table is {@code <prefix>_<index>} where the index is
         * {@code MOD(value, hashPartitions)}, always in {@code [0, hashPartitions)}.
         * The value is truncated toward zero to an integer; non-numeric values
         * match no table. Returns {@code null} when the value cannot be mapped.
         *
         * @param value the partition key value to resolve
         * @return the matching physical table name, or {@code null} if none matches
         */
        public String getHashTableName(String value) {
            if (!isHashType() || hashPartitions < 1) {
                return null;
            }
            Long number = toIntegerValue(value);
            if (number == null) {
                return null;
            }
            return viewNamePrefix + "_" + Math.floorMod(number, hashPartitions);
        }

        /**
         * Parses a partition key value as an integer: decimal values are
         * truncated toward zero (so {@code 5.9} becomes {@code 5}). Returns
         * {@code null} for values that are not numeric.
         */
        private static Long toIntegerValue(String s) {
            try {
                return new BigDecimal(s.trim()).longValue();
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private void warnOverlappingRanges() {
            for (int i = 0; i < ranges.size(); i++) {
                for (int j = i + 1; j < ranges.size(); j++) {
                    if (ranges.get(i).overlaps(ranges.get(j))) {
                        log.warn("Ranges of table '{}' overlap: [{}, {}] -> '{}' and [{}, {}] -> '{}'; the first match wins",
                                viewNamePrefix,
                                ranges.get(i).getLow(), ranges.get(i).getHigh(), ranges.get(i).getTableName(),
                                ranges.get(j).getLow(), ranges.get(j).getHigh(), ranges.get(j).getTableName());
                    }
                }
            }
        }

        private void warnOverlappingLists() {
            for (int i = 0; i < lists.size(); i++) {
                for (int j = i + 1; j < lists.size(); j++) {
                    for (String value : lists.get(i).getValues()) {
                        if (lists.get(j).contains(value)) {
                            log.warn("Lists of table '{}' overlap: value '{}' is present in both '{}' and '{}'; the first match wins",
                                    viewNamePrefix, value,
                                    lists.get(i).getTableName(), lists.get(j).getTableName());
                        }
                    }
                }
            }
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Compares two partition-mapping values numerically when both are numeric,
     * otherwise lexicographically. Shared by {@link RangeEntry} (interval
     * bounds) and {@link ListEntry} (discrete value equality, via {@code == 0}).
     */
    private static int compareValues(String a, String b) {
        if (isNumeric(a) && isNumeric(b)) {
            return new BigDecimal(a).compareTo(new BigDecimal(b));
        }
        return a.compareTo(b);
    }

    /**
     * An entry of a {@code partitionType=range} mapping: values in the inclusive
     * interval {@code [low, high]} route to the given physical table. A
     * {@code null} bound means unbounded (open) on that side. Bounds are
     * compared numerically when both sides are numeric, otherwise
     * lexicographically.
     */
    public static class RangeEntry {
        private final String low;
        private final String high;
        private final String tableName;

        public RangeEntry(String low, String high, String tableName) {
            this.low = low;
            this.high = high;
            this.tableName = tableName;
        }

        public String getLow() { return low; }
        public String getHigh() { return high; }
        public String getTableName() { return tableName; }

        public boolean matches(String value) {
            if (low != null && compare(value, low) < 0) {
                return false;
            }
            if (high != null && compare(value, high) > 0) {
                return false;
            }
            return true;
        }

        boolean overlaps(RangeEntry other) {
            return !endsBefore(this, other) && !endsBefore(other, this);
        }

        private static boolean endsBefore(RangeEntry a, RangeEntry b) {
            if (a.high == null || b.low == null) {
                return false;
            }
            return compare(a.high, b.low) < 0;
        }

        private static int compare(String a, String b) {
            return compareValues(a, b);
        }
    }

    /**
     * An entry of a {@code partitionType=list} mapping: each discrete value in
     * the list routes to the given physical table. Values are compared
     * numerically when both sides are numeric, otherwise with exact,
     * case-sensitive string equality.
     */
    public static class ListEntry {
        private final List<String> values;
        private final String tableName;

        public ListEntry(List<String> values, String tableName) {
            this.values = values;
            this.tableName = tableName;
        }

        public List<String> getValues() {
            return values;
        }

        public String getTableName() {
            return tableName;
        }

        public boolean contains(String value) {
            for (String candidate : values) {
                if (equal(value, candidate)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean equal(String a, String b) {
            return compareValues(a, b) == 0;
        }
    }

    private SQLProcessor sqlProcessor;
    private DbType dbType;
    private final List<TableConfig> tableConfigs = new ArrayList<>();

    private int maximumCacheSize = DEFAULT_MAXIMUM_CACHE_SIZE;

    public Configuration(DbType dbType, Properties info) {
        this.dbType = dbType;
        parseTableConfigs(info);
        parseCacheConfig(info);
        this.sqlProcessor = new SQLProcessor(this);
    }

    public Configuration(DbType dbType, String viewNamePrefix, String partitionType, String partitionKey) {
        this.dbType = dbType;
        this.tableConfigs.add(new TableConfig(viewNamePrefix, partitionType, partitionKey));
        this.sqlProcessor = new SQLProcessor(this);
    }

    private void parseTableConfigs(Properties info) {
        String prefix = getProperty(info, "viewNamePrefix");
        if (prefix != null) {
            String type = getProperty(info, "partitionType");
            String key = getProperty(info, "partitionKey");
            tableConfigs.add(parseTableConfig(info, prefix, type, key));
        }
        for (int i = 2; ; i++) {
            String suffixedPrefix = getProperty(info, "viewNamePrefix" + i);
            if (suffixedPrefix == null) {
                break;
            }
            String suffixedType = getProperty(info, "partitionType" + i);
            String suffixedKey = getProperty(info, "partitionKey" + i);
            tableConfigs.add(parseTableConfig(info, suffixedPrefix, suffixedType, suffixedKey));
        }
    }

    private TableConfig parseTableConfig(Properties info, String prefix, String type, String key) {
        TableConfig config = new TableConfig(prefix, type, key);
        if (config.isRangeType()) {
            parseRangeEntries(info, config, prefix);
        } else if (config.isListType()) {
            parseListEntries(info, config, prefix);
        } else if (config.isHashType()) {
            parseHashConfig(info, config, prefix);
        }
        return config;
    }

    private void parseHashConfig(Properties info, TableConfig config, String prefix) {
        String value = getProperty(info, prefix + ".hash.partitions");
        if (value == null || value.trim().isEmpty()) {
            log.warn("Table '{}' declares partitionType=hash but '{}'.hash.partitions was not set; queries will not be rewritten",
                    prefix, prefix);
            return;
        }
        try {
            int partitions = Integer.parseInt(value.trim());
            if (partitions < 1) {
                log.warn("Table '{}' declares {} partitions, which is not a positive integer; queries will not be rewritten",
                        prefix, value);
                return;
            }
            config.setHashPartitions(partitions);
        } catch (NumberFormatException e) {
            log.warn("Invalid partitions value '{}' for table '{}'; queries will not be rewritten", value, prefix);
        }
    }

    private void parseRangeEntries(Properties info, TableConfig config, String prefix) {
        for (int i = 1; ; i++) {
            String suffix = "." + i + ".";
            String low = getProperty(info, prefix + ".range" + suffix + "low");
            String high = getProperty(info, prefix + ".range" + suffix + "high");
            String table = getProperty(info, prefix + ".range" + suffix + "table");
            if (low == null && high == null && table == null) {
                break;
            }
            if (table == null || table.trim().isEmpty()) {
                log.warn("Range entry '{}'.range.{} has no target table; skipping", prefix, i);
                continue;
            }
            String tableName = table.trim();
            if (!SQLStatementTranslator.isValidTablePrefix(tableName)) {
                throw new IllegalArgumentException("Range entry '" + prefix + ".range." + i
                        + "' has invalid table name '" + tableName
                        + "'; must be a plain identifier or a schema-qualified name");
            }
            config.addRange(new RangeEntry(low, high, tableName));
        }
        if (config.getRanges().isEmpty()) {
            log.warn("Table '{}' declares partitionType=range but no ranges were configured; queries will not be rewritten",
                    prefix);
        } else {
            config.warnOverlappingRanges();
        }
    }

    private void parseListEntries(Properties info, TableConfig config, String prefix) {
        for (int i = 1; ; i++) {
            String suffix = "." + i + ".";
            String values = getProperty(info, prefix + ".list" + suffix + "values");
            String table = getProperty(info, prefix + ".list" + suffix + "table");
            if (values == null && table == null) {
                break;
            }
            if (table == null || table.trim().isEmpty()) {
                log.warn("List entry '{}'.list.{} has no target table; skipping", prefix, i);
                continue;
            }
            String tableName = table.trim();
            if (!SQLStatementTranslator.isValidTablePrefix(tableName)) {
                throw new IllegalArgumentException("List entry '" + prefix + ".list." + i
                        + "' has invalid table name '" + tableName
                        + "'; must be a plain identifier or a schema-qualified name");
            }
            List<String> parsedValues = new ArrayList<>();
            if (values != null) {
                for (String value : values.split(",")) {
                    String trimmed = value.trim();
                    if (!trimmed.isEmpty()) {
                        if (!SQLStatementTranslator.isValidTableSuffix(trimmed)) {
                            throw new IllegalArgumentException("List entry '" + prefix + ".list." + i
                                    + "' has invalid value '" + trimmed + "'; list values must be valid table suffixes");
                        }
                        parsedValues.add(trimmed);
                    }
                }
            }
            if (parsedValues.isEmpty()) {
                log.warn("List entry '{}'.list.{} has no values; skipping", prefix, i);
                continue;
            }
            config.addList(new ListEntry(parsedValues, tableName));
        }
        if (config.getLists().isEmpty()) {
            log.warn("Table '{}' declares partitionType=list but no lists were configured; queries will not be rewritten",
                    prefix);
        } else {
            config.warnOverlappingLists();
        }
    }

    private void parseCacheConfig(Properties info) {
        String value = getProperty(info, "cache.maximumSize");
        if (value == null) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0) {
                this.maximumCacheSize = parsed;
                return;
            }
            log.warn("Invalid cache.maximumSize value '{}': must be a positive integer; using default {}",
                    value, DEFAULT_MAXIMUM_CACHE_SIZE);
        } catch (NumberFormatException e) {
            log.warn("Invalid cache.maximumSize value '{}': not a number; using default {}",
                    value, DEFAULT_MAXIMUM_CACHE_SIZE);
        }
    }

    public DbType getDbType() {
        return dbType;
    }

    public void setDbType(DbType dbType) {
        this.dbType = dbType;
    }

    public int getTableConfigCount() {
        return tableConfigs.size();
    }

    public int getMaximumCacheSize() {
        return maximumCacheSize;
    }

    public TableConfig getTableConfig(int index) {
        return tableConfigs.get(index);
    }

    public List<TableConfig> getTableConfigs() {
        return Collections.unmodifiableList(tableConfigs);
    }

    public TableConfig getTableConfigForTable(String tableName) {
        for (TableConfig config : tableConfigs) {
            if (tableName.equalsIgnoreCase(config.getViewNamePrefix())) {
                return config;
            }
        }
        return null;
    }

    public SQLStatementTranslator resolve(String sql) {
        return sqlProcessor.resolve(sql);
    }

    public String rewrite(String sql) {
        return sqlProcessor.rewrite(sql);
    }

    private static String getProperty(Properties p, String key) {
        String value = p.getProperty("dinuk." + key);
        if (value == null) {
            value = p.getProperty(key);
        }
        return value;
    }
}
