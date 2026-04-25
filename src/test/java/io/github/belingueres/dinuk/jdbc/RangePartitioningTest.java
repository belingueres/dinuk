package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

  import java.math.BigDecimal;
  import java.sql.SQLException;
  import java.util.HashMap;
  import java.util.Map;
  import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

class RangePartitioningTest {

    private SQLProcessor numericRange;
    private SQLProcessor stringRange;
    private SQLProcessor openRange;

    private SQLProcessor newProcessor(Properties props) {
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    @BeforeEach
    void setUp() {
        Properties numeric = new Properties();
        numeric.setProperty("viewNamePrefix", "orders");
        numeric.setProperty("partitionType", "range");
        numeric.setProperty("partitionKey", "id");
        numeric.setProperty("orders.range.1.low", "0");
        numeric.setProperty("orders.range.1.high", "10");
        numeric.setProperty("orders.range.1.table", "orders_0");
        numeric.setProperty("orders.range.2.low", "11");
        numeric.setProperty("orders.range.2.high", "20");
        numeric.setProperty("orders.range.2.table", "orders_1");
        numericRange = newProcessor(numeric);

        Properties string = new Properties();
        string.setProperty("viewNamePrefix", "orders");
        string.setProperty("partitionType", "range");
        string.setProperty("partitionKey", "id");
        string.setProperty("orders.range.1.low", "A");
        string.setProperty("orders.range.1.high", "M");
        string.setProperty("orders.range.1.table", "orders_am");
        string.setProperty("orders.range.2.low", "N");
        string.setProperty("orders.range.2.high", "Z");
        string.setProperty("orders.range.2.table", "orders_nz");
        stringRange = newProcessor(string);

        Properties open = new Properties();
        open.setProperty("viewNamePrefix", "orders");
        open.setProperty("partitionType", "range");
        open.setProperty("partitionKey", "id");
        open.setProperty("orders.range.1.high", "10");
        open.setProperty("orders.range.1.table", "orders_le10");
        open.setProperty("orders.range.2.low", "11");
        open.setProperty("orders.range.2.table", "orders_gt10");
        openRange = newProcessor(open);
    }

    @Test
    @DisplayName("Value inside the first range routes to the first physical table")
    void testSimpleSelectFirstRange() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=5 AND name='Alice'";
        assertSqlEquals("SELECT name, id, amount FROM orders_0 WHERE id=5 AND name='Alice'", numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("Value inside the second range routes to the second physical table")
    void testSimpleSelectSecondRange() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=15 AND name='Alice'";
        assertSqlEquals("SELECT name, id, amount FROM orders_1 WHERE id=15 AND name='Alice'", numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("Range bounds are inclusive: value equal to low or high matches")
    void testInclusiveBounds() {
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=0 AND name='A'",
                numericRange.rewrite("SELECT name, id FROM orders WHERE id=0 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=10 AND name='A'",
                numericRange.rewrite("SELECT name, id FROM orders WHERE id=10 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_1 WHERE id=11 AND name='A'",
                numericRange.rewrite("SELECT name, id FROM orders WHERE id=11 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_1 WHERE id=20 AND name='A'",
                numericRange.rewrite("SELECT name, id FROM orders WHERE id=20 AND name='A'"));
    }

    @Test
    @DisplayName("Value outside every declared range leaves the SQL unchanged")
    void testValueOutsideAllRangesNoTranslation() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=25 AND name='Alice'";
        assertSqlEquals(sql, numericRange.rewrite(sql));
        assertNull(numericRange.resolve(sql));
    }

    @Test
    @DisplayName("Multi-table JOIN with an out-of-range fixed partition key leaves the SQL unchanged")
    void testJoinOutOfRangeLeavesUnchanged() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "0");
        props.setProperty("orders.range.1.high", "10");
        props.setProperty("orders.range.1.table", "orders_0");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        SQLProcessor multi = newProcessor(props);

        String sql = "SELECT o.name, u.name FROM orders o JOIN users u ON o.name = u.name WHERE o.id = 25 AND u.id = 1";
        assertSqlEquals(sql, multi.rewrite(sql));
        assertNull(multi.resolve(sql));
    }

    @Test
    @DisplayName("String partition key values are matched against string bounds")
    void testStringBounds() {
        assertSqlEquals("SELECT name, id FROM orders_am WHERE id='C' AND name='A'",
                stringRange.rewrite("SELECT name, id FROM orders WHERE id='C' AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_nz WHERE id='Q' AND name='A'",
                stringRange.rewrite("SELECT name, id FROM orders WHERE id='Q' AND name='A'"));
        String outOfRange = "SELECT name, id FROM orders WHERE id='?' AND name='A'";
        assertNull(stringRange.resolve(outOfRange));
    }

    @Test
    @DisplayName("A single bound is allowed: omitted low means -infinity, omitted high means +infinity")
    void testOpenEndedRanges() {
        assertSqlEquals("SELECT name, id FROM orders_le10 WHERE id=5 AND name='A'",
                openRange.rewrite("SELECT name, id FROM orders WHERE id=5 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_le10 WHERE id=-5 AND name='A'",
                openRange.rewrite("SELECT name, id FROM orders WHERE id=-5 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_gt10 WHERE id=100 AND name='A'",
                openRange.rewrite("SELECT name, id FROM orders WHERE id=100 AND name='A'"));
    }

    @Test
    @DisplayName("UPDATE routes to the physical table for the value range")
    void testUpdateRange() {
        assertSqlEquals("UPDATE orders_0 SET name='Bob' WHERE id=5",
                numericRange.rewrite("UPDATE orders SET name='Bob' WHERE id=5"));
        assertSqlEquals("UPDATE orders_1 SET name='Bob' WHERE id=15 AND status='active'",
                numericRange.rewrite("UPDATE orders SET name='Bob' WHERE id=15 AND status='active'"));
    }

    @Test
    @DisplayName("DELETE routes to the physical table for the value range")
    void testDeleteRange() {
        assertSqlEquals("DELETE FROM orders_1 WHERE id=15", numericRange.rewrite("DELETE FROM orders WHERE id=15"));
        assertSqlEquals("DELETE FROM orders_0 WHERE id=5", numericRange.rewrite("DELETE FROM orders WHERE id=5"));
    }

    @Test
    @DisplayName("INSERT routes to the physical table for the value range and keeps the partition key column")
    void testInsertRange() {
        String sql = "INSERT INTO orders (id, name, amount) VALUES (12, 'Alice', 2.34)";
        assertSqlEquals("INSERT INTO orders_1 (id, name, amount) VALUES (12, 'Alice', 2.34)", numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A parameterized partition key defers resolution and picks the table at execution time")
    void testDeferredSelectResolvesAtRuntime() throws SQLException {
        String sql = "SELECT name, id, amount FROM orders WHERE id=? AND name='Alice'";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("SELECT name, id, amount FROM {deferred} WHERE id={deferred_key} AND name='Alice'",
                translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());

        assertSqlEquals("SELECT name, id, amount FROM orders_0 WHERE id=5 AND name='Alice'", translator.getFinalSql(5));
        assertSqlEquals("SELECT name, id, amount FROM orders_1 WHERE id=15 AND name='Alice'", translator.getFinalSql(15));
    }

    @Test
    @DisplayName("A parameterized value matching no range fails at execution time")
    void testDeferredSelectOutOfRangeThrows() throws SQLException {
        String sql = "SELECT name, amount FROM orders WHERE id=?";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertThrows(SQLException.class, () -> translator.getFinalSql(99));
    }

    @Test
    @DisplayName("Deferred UPDATE resolves the physical table at execution time")
    void testDeferredUpdateResolvesAtRuntime() throws SQLException {
        String sql = "UPDATE orders SET name='Bob' WHERE id=?";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("UPDATE {deferred} SET name='Bob' WHERE id={deferred_key}", translator.getRewrittenSql());
        assertSqlEquals("UPDATE orders_0 SET name='Bob' WHERE id=7", translator.getFinalSql(7));
    }

    @Test
    @DisplayName("Deferred INSERT resolves the physical table at execution time")
    void testDeferredInsertResolvesAtRuntime() throws SQLException {
        String sql = "INSERT INTO orders (name, id, amount) VALUES ('Alice', ?, 2.34)";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO {deferred} (name, id, amount) VALUES ('Alice', {deferred_key}, 2.34)",
                translator.getRewrittenSql());
        assertSqlEquals("INSERT INTO orders_0 (name, id, amount) VALUES ('Alice', 7, 2.34)", translator.getFinalSql(7));
    }

    @Test
    @DisplayName("A single configured range table inside a join routes to the physical table")
    void testRangeInCommaJoin() {
        String sql = "SELECT o.name, o.id, c.name FROM orders o, customer c WHERE o.id=5 AND o.name=c.name";
        assertSqlEquals("SELECT o.name, o.id, c.name FROM orders_0 o, customer c WHERE o.name=c.name AND o.id=5",
                numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A deferred range table in a 3+ table COMMA join uses the deferred placeholder")
    void testRangeDeferredInMultiTableCommaJoin() {
        String sql = "SELECT o.id, u.id, p.id FROM orders o, users u, products p "
                + "WHERE o.id = u.id AND u.id = p.id AND o.id = ?";
        assertSqlEquals("SELECT o.id, u.id, p.id "
                + "FROM {deferred1} o, users u, products p "
                + "WHERE o.id = u.id AND u.id = p.id AND o.id = {deferred_key1}",
                numericRange.rewrite(sql));
    }

    private SQLProcessor twoTableRange() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "-100");
        props.setProperty("orders.range.1.high", "100");
        props.setProperty("orders.range.1.table", "orders_neg");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "range");
        props.setProperty("partitionKey2", "id");
        props.setProperty("users.range.1.low", "-100");
        props.setProperty("users.range.1.high", "100");
        props.setProperty("users.range.1.table", "users_neg");
        return newProcessor(props);
    }

    private SQLStatementTranslator twoTableDeferred(SQLProcessor p) {
        return p.resolve("SELECT o.id, u.id FROM orders o JOIN users u "
                + "ON o.user_id = u.id WHERE o.id = ? AND u.id = ?");
    }

    @Test
    @DisplayName("multi-table range deferred with a negative value routes correctly")
    void multiTableRangeDeferredNegativeValueRoutes() throws Exception {
        SQLProcessor p = twoTableRange();
        SQLStatementTranslator t = twoTableDeferred(p);
        Map<Integer, Object> values = new HashMap<>();
        values.put(1, -50);
        values.put(2, 30);
        assertSqlEquals("SELECT o.id, u.id FROM orders_neg o JOIN users_neg u "
                + "ON o.user_id = u.id WHERE o.id = -50 AND u.id = 30",
                t.getFinalSql(values));
    }

    @Test
    @DisplayName("multi-table range deferred with a decimal value routes correctly")
    void multiTableRangeDeferredDecimalValueRoutes() throws Exception {
        SQLProcessor p = twoTableRange();
        SQLStatementTranslator t = twoTableDeferred(p);
        Map<Integer, Object> values = new HashMap<>();
        values.put(1, new BigDecimal("7.5"));
        values.put(2, 30);
        assertSqlEquals("SELECT o.id, u.id FROM orders_neg o JOIN users_neg u "
                + "ON o.user_id = u.id WHERE o.id = 7.5 AND u.id = 30",
                t.getFinalSql(values));
    }

    @Test
    @DisplayName("multi-table deferred value containing a placeholder token is refused")
    void multiTableDeferredValueWithPlaceholderTokenRefused() throws Exception {
        SQLProcessor p = twoTableRange();
        SQLStatementTranslator t = twoTableDeferred(p);
        Map<Integer, Object> values = new HashMap<>();
        values.put(1, "abc{deferred_key2}def");
        values.put(2, 30);
        SQLException ex = assertThrows(SQLException.class, () -> t.getFinalSql(values));
        assertTrue(ex.getMessage().contains("placeholder"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("A fixed range table in a 3+ table JOIN chain routes to the physical table")
    void testRangeFixedInMultiTableJoinChain() {
        String sql = "SELECT c.customer_id, o.id, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders o ON c.customer_id = o.customer_id "
                + "INNER JOIN products p ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' AND o.id = 15 "
                + "ORDER BY o.order_date DESC";
        assertSqlEquals("SELECT c.customer_id, o.id, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders_1 o ON c.customer_id = o.customer_id "
                + "INNER JOIN products p ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' AND o.id = 15 ORDER BY o.order_date DESC",
                numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A deferred range table in a 3+ table JOIN chain uses the deferred placeholder")
    void testRangeDeferredInMultiTableJoinChain() {
        String sql = "SELECT c.customer_id, o.id, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders o ON c.customer_id = o.customer_id "
                + "INNER JOIN products p ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' AND o.id = ? "
                + "ORDER BY o.order_date DESC";
        assertSqlEquals("SELECT c.customer_id, o.id, p.product_name "
                + "FROM customers c "
                + "INNER JOIN {deferred1} o ON c.customer_id = o.customer_id "
                + "INNER JOIN products p ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' AND o.id = {deferred_key1} ORDER BY o.order_date DESC",
                numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A range table keeps its partition key references in other join conditions")
    void testRangePartitionKeyRefsNotReplacedInCommaJoin() {
        String sql = "SELECT o.id, u.id, p.id FROM orders o, users u, products p "
                + "WHERE o.id = u.id AND u.id = p.id AND o.id = 5";
        assertSqlEquals("SELECT o.id, u.id, p.id FROM orders_0 o, users u, products p "
                + "WHERE o.id = u.id AND u.id = p.id AND o.id = 5", numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A fixed range table keeps its partition key condition when it sits in the ON clause")
    void testRangeFixedKeyInOnClauseRetained() {
        String sql = "SELECT o.id, c.name FROM customers c JOIN orders o ON c.id = o.customer_id AND o.id = 5";
        assertSqlEquals("SELECT o.id, c.name FROM customers c JOIN orders_0 o ON c.id = o.customer_id AND o.id = 5",
                numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A deferred range table keeps its partition key condition when it sits in the ON clause")
    void testRangeDeferredKeyInOnClauseRetained() throws SQLException {
        String sql = "SELECT o.id, c.name FROM customers c JOIN orders o ON c.id = o.customer_id AND o.id = ?";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("SELECT o.id, c.name FROM customers c JOIN {deferred1} o ON c.id = o.customer_id AND o.id = {deferred_key1}",
                translator.getRewrittenSql());

        Map<Integer, Object> params = new HashMap<>();
        params.put(1, 5);
        assertSqlEquals("SELECT o.id, c.name FROM customers c JOIN orders_0 o ON c.id = o.customer_id AND o.id = 5",
                translator.getFinalSql(params));
    }

    @Test
    @DisplayName("Overlap is tolerated with first declared range winning")
    void testOverlappingRangesFirstWins() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "0");
        props.setProperty("orders.range.1.high", "20");
        props.setProperty("orders.range.1.table", "orders_wide");
        props.setProperty("orders.range.2.low", "10");
        props.setProperty("orders.range.2.high", "30");
        props.setProperty("orders.range.2.table", "orders_narrow");
        SQLProcessor overlapping = newProcessor(props);

        assertSqlEquals("SELECT name, id FROM orders_wide WHERE id=15 AND name='A'",
                overlapping.rewrite("SELECT name, id FROM orders WHERE id=15 AND name='A'"));
        assertSqlEquals("SELECT name, id FROM orders_narrow WHERE id=30 AND name='A'",
                overlapping.rewrite("SELECT name, id FROM orders WHERE id=30 AND name='A'"));
    }

    @Test
    @DisplayName("Deferred INSERT keeps the partition key column and substitutes the runtime placeholder")
    void testDeferredInsertKeepsPartitionKey() throws SQLException {
        String sql = "INSERT INTO orders (name, id, amount) VALUES ('Alice', ?, 2.34)";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO {deferred} (name, id, amount) VALUES ('Alice', {deferred_key}, 2.34)",
                translator.getRewrittenSql());
        assertSqlEquals("INSERT INTO orders_1 (name, id, amount) VALUES ('Alice', 12, 2.34)",
                translator.getFinalSql(12));
    }

    @Test
    @DisplayName("A deferred range UNION side keeps the partition key condition in WHERE")
    void testRangeUnionDeferred() {
        String sql = "SELECT name, id FROM orders WHERE id=? UNION SELECT name, id FROM archive WHERE id=?";
        assertSqlEquals("SELECT name, id FROM {deferred} WHERE id={deferred_key} "
                + "UNION SELECT name, id FROM archive WHERE id=?",
                numericRange.rewrite(sql));
    }

    @Test
    @DisplayName("A deferred range value not first in WHERE still resolves to the right placeholder")
    void testDeferredSelectPartitionKeyLater() throws SQLException {
        String sql = "SELECT name, amount FROM orders WHERE name='Alice' AND id=?";
        SQLStatementTranslator translator = numericRange.resolve(sql);
        assertNotNull(translator);
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
        assertSqlEquals("SELECT name, amount FROM {deferred} WHERE name='Alice' AND id={deferred_key}",
                translator.getRewrittenSql());
        assertSqlEquals("SELECT name, amount FROM orders_1 WHERE name='Alice' AND id=15",
                translator.getFinalSql(15));
    }

    @Test
    @DisplayName("Configured ranges are exposed on the table config")
    void testConfigExposesRanges() {
        Configuration.TableConfig config = numericRangeConfig().getTableConfig(0);
        assertTrue(config.isRangeType());
        assertEquals(2, config.getRanges().size());
        assertEquals("orders_0", config.getRangeTableName("5"));
        assertEquals("orders_1", config.getRangeTableName("11"));
        assertNull(config.getRangeTableName("25"));
    }

    private Configuration numericRangeConfig() {
        Properties numeric = new Properties();
        numeric.setProperty("viewNamePrefix", "orders");
        numeric.setProperty("partitionType", "range");
        numeric.setProperty("partitionKey", "id");
        numeric.setProperty("orders.range.1.low", "0");
        numeric.setProperty("orders.range.1.high", "10");
        numeric.setProperty("orders.range.1.table", "orders_0");
        numeric.setProperty("orders.range.2.low", "11");
        numeric.setProperty("orders.range.2.high", "20");
        numeric.setProperty("orders.range.2.table", "orders_1");
        return new Configuration(DbType.informix, numeric);
    }
}