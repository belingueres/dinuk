package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

class ConfigurationValidationTest {

    private Properties rangeProps(String prefix) {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", prefix);
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty(prefix + ".range.1.low", "0");
        props.setProperty(prefix + ".range.1.high", "9");
        props.setProperty(prefix + ".range.1.table", prefix + "_0");
        return props;
    }

    @Test
    @DisplayName("viewNamePrefix must be a valid table prefix")
    void invalidViewNamePrefixThrows() {
        Properties props = rangeProps("orders 1");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("viewNamePrefix with unsafe characters is rejected")
    void unsafeViewNamePrefixThrows() {
        Properties props = rangeProps("orders;drop");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("schema-qualified viewNamePrefix is accepted")
    void schemaQualifiedViewNamePrefixAccepted() {
        assertDoesNotThrow(() -> new Configuration(DbType.informix, rangeProps("myschema.orders")));
    }

    @Test
    @DisplayName("numbered viewNamePrefix entries are validated too")
    void invalidNumberedViewNamePrefixThrows() {
        Properties props = rangeProps("orders");
        props.setProperty("viewNamePrefix2", "users 1");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("partitionKey must be a plain identifier")
    void invalidPartitionKeyThrows() {
        Properties props = rangeProps("orders");
        props.setProperty("partitionKey", "id col");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("invalid viewNamePrefix is rejected through the direct constructor too")
    void invalidPrefixThroughDirectConstructorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Configuration(DbType.informix, "orders 1", "key", "id"));
    }

    @Test
    @DisplayName("invalid partitionKey is rejected through the direct constructor too")
    void invalidKeyThroughDirectConstructorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Configuration(DbType.informix, "orders", "key", "id;drop"));
    }

    @Test
    @DisplayName("range table names must be valid table identifiers")
    void invalidRangeTableNameThrows() {
        Properties props = rangeProps("orders");
        props.setProperty("orders.range.1.table", "orders 0");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("schema-qualified range table names are accepted")
    void schemaQualifiedRangeTableNameAccepted() {
        Properties props = rangeProps("orders");
        props.setProperty("orders.range.1.table", "myschema.orders_0");
        assertDoesNotThrow(() -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("schema-qualified list table names are accepted")
    void schemaQualifiedListTableNameAccepted() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us,ca");
        props.setProperty("orders.list.1.table", "myschema.orders_na");
        assertDoesNotThrow(() -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("missing partitionKey for key partitionType throws IllegalArgumentException, not NPE")
    void missingPartitionKeyForKeyTypeThrows() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("missing partitionKey for range partitionType throws IllegalArgumentException, not NPE")
    void missingPartitionKeyForRangeTypeThrows() {
        Properties props = rangeProps("orders");
        props.remove("partitionKey");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("missing partitionKey for list partitionType throws IllegalArgumentException, not NPE")
    void missingPartitionKeyForListTypeThrows() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("orders.list.1.values", "us,ca");
        props.setProperty("orders.list.1.table", "orders_na");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("missing partitionKey for hash partitionType throws IllegalArgumentException, not NPE")
    void missingPartitionKeyForHashTypeThrows() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "hash");
        props.setProperty("orders.hash.partitions", "4");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("missing partitionKey for numbered table config throws IllegalArgumentException, not NPE")
    void missingPartitionKeyForNumberedConfigThrows() {
        Properties props = rangeProps("orders");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("blank partitionKey throws IllegalArgumentException, not NPE")
    void blankPartitionKeyThrows() {
        Properties props = rangeProps("orders");
        props.setProperty("partitionKey", "  ");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }
}