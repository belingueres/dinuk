package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

class ListPartitioningTest {

    private SQLProcessor regionList;
    private SQLProcessor numericList;

    private SQLProcessor newProcessor(Properties props) {
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    @BeforeEach
    void setUp() {
        Properties region = new Properties();
        region.setProperty("viewNamePrefix", "orders");
        region.setProperty("partitionType", "list");
        region.setProperty("partitionKey", "region");
        region.setProperty("orders.list.1.values", "us, ca, mx");
        region.setProperty("orders.list.1.table", "orders_na");
        region.setProperty("orders.list.2.values", "uk,ie");
        region.setProperty("orders.list.2.table", "orders_eu");
        regionList = newProcessor(region);

        Properties numeric = new Properties();
        numeric.setProperty("viewNamePrefix", "orders");
        numeric.setProperty("partitionType", "list");
        numeric.setProperty("partitionKey", "region");
        numeric.setProperty("orders.list.1.values", "1,2,3");
        numeric.setProperty("orders.list.1.table", "orders_low");
        numeric.setProperty("orders.list.2.values", "4,5");
        numeric.setProperty("orders.list.2.table", "orders_high");
        numericList = newProcessor(numeric);
    }

    @Test
    @DisplayName("list values that are not valid table suffixes are rejected at configuration time")
    void invalidListValueThrowsAtConfiguration() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us,New York");
        props.setProperty("orders.list.1.table", "orders_na");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Configuration(DbType.informix, props));
        assertTrue(ex.getMessage().contains("New York"), ex.getMessage());
    }

    @Test
    @DisplayName("list values with unsafe characters are rejected at configuration time")
    void unsafeListValueThrowsAtConfiguration() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us-1");
        props.setProperty("orders.list.1.table", "orders_na");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("list table names that are not plain identifiers are rejected at configuration time")
    void invalidListTableNameThrowsAtConfiguration() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us");
        props.setProperty("orders.list.1.table", "orders na");
        assertThrows(IllegalArgumentException.class, () -> new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("well-formed list configuration constructs without error")
    void validListConfigConstructs() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us,ca");
        props.setProperty("orders.list.1.table", "orders_na");
        new Configuration(DbType.informix, props);
    }

    @Test
    @DisplayName("list configuration is parsed into per-table list entries")
    void configParsing() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us, ca, mx");
        props.setProperty("orders.list.1.table", "orders_na");
        props.setProperty("orders.list.2.values", "uk,ie");
        props.setProperty("orders.list.2.table", "orders_eu");
        Configuration config = new Configuration(DbType.informix, props);
        org.junit.jupiter.api.Assertions.assertEquals(1, config.getTableConfigCount());
        Configuration.TableConfig tableConfig = config.getTableConfig(0);
        assertTrue(tableConfig.isListType());
        assertTrue(tableConfig.isValueMapped());
        org.junit.jupiter.api.Assertions.assertEquals(2, tableConfig.getLists().size());
        org.junit.jupiter.api.Assertions.assertEquals("us", tableConfig.getLists().get(0).getValues().get(0));
        org.junit.jupiter.api.Assertions.assertEquals("orders_na", tableConfig.getLists().get(0).getTableName());
        org.junit.jupiter.api.Assertions.assertEquals("uk", tableConfig.getLists().get(1).getValues().get(0));
        org.junit.jupiter.api.Assertions.assertEquals("orders_eu", tableConfig.getLists().get(1).getTableName());
    }

    @Test
    @DisplayName("SELECT routes to the physical table of the matching list and keeps the partition key condition")
    void selectRoutesToMatchingListTable() {
        String sql = "SELECT name, region FROM orders WHERE region='us' AND name='Alice'";
        assertSqlEquals("SELECT name, region FROM orders_na WHERE region='us' AND name='Alice'",
                regionList.rewrite(sql));
    }

    @Test
    @DisplayName("SELECT routes to the second list table")
    void selectRoutesToSecondListTable() {
        String sql = "SELECT name, region FROM orders WHERE region='ie' AND name='Alice'";
        assertSqlEquals("SELECT name, region FROM orders_eu WHERE region='ie' AND name='Alice'",
                regionList.rewrite(sql));
    }

    @Test
    @DisplayName("A value matching no list leaves the SQL unchanged")
    void selectValueNotInAnyListIsUnchanged() {
        String sql = "SELECT name, region FROM orders WHERE region='jp' AND name='Alice'";
        assertSqlEquals(sql, regionList.rewrite(sql));
        assertNull(regionList.resolve(sql));
    }

    @Test
    @DisplayName("INSERT routes to the list table and keeps the partition key column")
    void insertRoutesToListTable() {
        String sql = "INSERT INTO orders (region, name, amount) VALUES ('ca', 'Alice', 2.34)";
        assertSqlEquals("INSERT INTO orders_na (region, name, amount) VALUES ('ca', 'Alice', 2.34)",
                regionList.rewrite(sql));
    }

    @Test
    @DisplayName("UPDATE routes to the list table and keeps the partition key condition")
    void updateRoutesToListTable() {
        String sql = "UPDATE orders SET name='Bob' WHERE region='uk' AND id=7";
        assertSqlEquals("UPDATE orders_eu SET name='Bob' WHERE region='uk' AND id=7", regionList.rewrite(sql));
    }

    @Test
    @DisplayName("A parameterized partition key defers resolution and picks the table at execution time")
    void deferredSelectResolvesAtRuntime() throws SQLException {
        String sql = "SELECT name, region FROM orders WHERE region=? AND name='Alice'";
        SQLStatementTranslator translator = regionList.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("SELECT name, region FROM {deferred} WHERE region={deferred_key} AND name='Alice'",
                translator.getRewrittenSql());

        assertSqlEquals("SELECT name, region FROM orders_na WHERE region='us' AND name='Alice'",
                translator.getFinalSql("us"));
        assertSqlEquals("SELECT name, region FROM orders_eu WHERE region='uk' AND name='Alice'",
                translator.getFinalSql("uk"));
    }

    @Test
    @DisplayName("A parameterized value matching no list fails at execution time")
    void deferredSelectUnmatchedValueThrows() throws SQLException {
        String sql = "SELECT name, region FROM orders WHERE region=?";
        SQLStatementTranslator translator = regionList.resolve(sql);
        assertNotNull(translator);
        assertThrows(SQLException.class, () -> translator.getFinalSql("jp"));
    }

    @Test
    @DisplayName("Deferred INSERT resolves the physical list table at execution time")
    void deferredInsertResolvesAtRuntime() throws SQLException {
        String sql = "INSERT INTO orders (name, region, amount) VALUES ('Alice', ?, 2.34)";
        SQLStatementTranslator translator = regionList.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO {deferred} (name, region, amount) VALUES ('Alice', {deferred_key}, 2.34)",
                translator.getRewrittenSql());
        assertSqlEquals("INSERT INTO orders_na (name, region, amount) VALUES ('Alice', 'mx', 2.34)",
                translator.getFinalSql("mx"));
    }

    @Test
    @DisplayName("Numeric list values match numerically: 1.0 hits the list entry 1")
    void numericAwareMatching() {
        assertSqlEquals("SELECT name, region FROM orders_low WHERE region=1.0 AND name='A'",
                numericList.rewrite("SELECT name, region FROM orders WHERE region=1.0 AND name='A'"));
    }

    @Test
    @DisplayName("String list values match case-sensitively: 'US' does not hit the entry 'us'")
    void caseSensitiveStringMatching() {
        String sql = "SELECT name, region FROM orders WHERE region='US' AND name='A'";
        assertSqlEquals(sql, numericList.rewrite(sql));
        assertNull(numericList.resolve(sql));
    }

    @Test
    @DisplayName("mixed list + key tables in the same query")
    void mixedListAndKeyJoin() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us,ca,mx");
        props.setProperty("orders.list.1.table", "orders_na");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "uid");
        SQLProcessor processor = newProcessor(props);

        String sql = "SELECT u.username, o.amount FROM users u, orders o "
                + "WHERE u.username=o.owner AND u.uid=7 AND o.region='us'";
        assertSqlEquals("SELECT u.username, o.amount FROM users_7 u, orders_na o "
                + "WHERE u.username=o.owner AND o.region='us'", processor.rewrite(sql));
    }
}
