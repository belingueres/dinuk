package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

class SQLProcessorTest {

    private static SQLProcessor processor;

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        Configuration configuration = new Configuration(DbType.informix, "orders", "key", "id");
        processor = new SQLProcessor(configuration );
    }

    @BeforeEach
    void setUp() throws Exception {
    }

    @Test
    @DisplayName("The select uses orders table but if * is present, no translation is performed")
    void testSelectStatementWithPartitionedTableWithAsterisk() {
        String sql = "SELECT * from orders where id=1 and name='Alice'";
        String rewritten = processor.rewrite(sql);
        String expected = sql;
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and it rewrite it, preserving column order")
    void testSelectStatementWithPartitionedTable() {
        String sql = "SELECT name, id, amount from orders where id=1 and name='Alice'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 1 id, amount from orders_1 where name='Alice'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and it rewrite it, preserving column order")
    void testSelectStatementWithPartitionedTable2() {
        String sql = "SELECT id, name, amount from orders where id=2 and name='Bob'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT 2 id, name, amount from orders_2 where name='Bob'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and it rewrite it, preserving column order, with table alias")
    void testSelectStatementWithPartitionedTableWithTableAlias() {
        String sql = "SELECT t.id, t.name, t.amount from orders t where t.id=2 and t.name='Bob'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT 2 id, t.name, t.amount from orders_2 t where t.name='Bob'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and the WHERE condition detects an OR where the partition key is involved => no rewritting")
    void testSelectStatementWithPartitionedTableWithOrConditionOnKey() {
        String sql = "SELECT t.id, t.name, t.amount from orders t where t.id=2 or t.name='Bob'";
        String rewritten = processor.rewrite(sql);
        String expected = sql;
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and other table where the partition key is involved in a JOIN and partition key is fixed")
    void testSelectStatementWithPartitionedTableWithJoin() {
        String sql = "SELECT c.customer_id, c.name, o.id, o.name, o.amount " +
                "from " +
                "orders o, " +
                "customer c " +
                "where "+
                "c.customer_id = o.name " +
                "and o.id=2 " +
                "and (o.name='Bob' or c.address is null)";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT c.customer_id, c.name, 2 id, o.name, o.amount " +
                "from " +
                "orders_2 o, " +
                "customer c " +
                "where "+
                "c.customer_id = o.name " +
                "and (o.name='Bob' or c.address is null)";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("The select uses orders table and other table where the partition key is involved in a COMMA join and partition key is deferred (parameter)")
    void testSelectStatementWithPartitionedTableWithJoinDeferred() {
        String sql = "SELECT c.customer_id, c.name, o.id, o.name, o.amount " +
                "from " +
                "orders o, " +
                "customer c " +
                "where "+
                "c.customer_id = o.name " +
                "and o.id=? " +
                "and (o.name='Bob' or c.address is null)";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT c.customer_id, c.name, {deferred_key} id, o.name, o.amount " +
                "from " +
                "orders_{deferred} o, " +
                "customer c " +
                "where "+
                "c.customer_id = o.name " +
                "and (o.name='Bob' or c.address is null)";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("UPDATE with fixed partition key - removes partition key from WHERE")
    void testUpdateStatementWithFixedPartitionKey() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=2";
        String rewritten = processor.rewrite(sql);
        String expected = "UPDATE orders_2 SET name='Bob'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("UPDATE with partition key and other conditions - keeps other conditions")
    void testUpdateStatementWithPartitionKeyAndOtherConditions() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=2 AND status='active'";
        String rewritten = processor.rewrite(sql);
        String expected = "UPDATE orders_2 SET name='Bob' WHERE status='active'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("UPDATE with OR on partition key - no rewriting")
    void testUpdateStatementWithOrConditionOnKey() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=2 OR status='active'";
        String rewritten = processor.rewrite(sql);
        String expected = sql;
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("UPDATE without WHERE clause - returns unchanged")
    void testUpdateStatementWithoutWhere() {
        String sql = "UPDATE orders SET name='Bob'";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals(sql, rewritten);
    }

    @Test
    @DisplayName("DELETE with fixed partition key - removes partition key from WHERE")
    void testDeleteStatementWithFixedPartitionKey() {
        String sql = "DELETE FROM orders WHERE id=2";
        String rewritten = processor.rewrite(sql);
        String expected = "DELETE FROM orders_2";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("DELETE with partition key and other conditions - keeps other conditions")
    void testDeleteStatementWithPartitionKeyAndOtherConditions() {
        String sql = "DELETE FROM orders WHERE id=2 AND status='inactive'";
        String rewritten = processor.rewrite(sql);
        String expected = "DELETE FROM orders_2 WHERE status='inactive'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("DELETE with OR on partition key - no rewriting")
    void testDeleteStatementWithOrConditionOnKey() {
        String sql = "DELETE FROM orders WHERE id=2 OR status='inactive'";
        String rewritten = processor.rewrite(sql);
        String expected = sql;
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("DELETE without WHERE clause - returns unchanged")
    void testDeleteStatementWithoutWhere() {
        String sql = "DELETE FROM orders";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals(sql, rewritten);
    }

    @Test
    @DisplayName("UPDATE with parameter placeholder for partition key - generates deferred SQL")
    void testUpdateStatementWithParameterPartitionKey() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=?";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("UPDATE orders_{deferred} SET name='Bob'", translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("UPDATE with parameter placeholder for partition key and other conditions")
    void testUpdateStatementWithParameterPartitionKeyAndOtherConditions() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=? AND status='active'";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("UPDATE orders_{deferred} SET name='Bob' WHERE status='active'", translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("DELETE with parameter placeholder for partition key and other conditions")
    void testDeleteStatementWithParameterPartitionKeyAndOtherConditions() {
        String sql = "DELETE FROM orders WHERE id=? AND status='inactive'";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("DELETE FROM orders_{deferred} WHERE status='inactive'", translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("DELETE with parameter placeholder for partition key - generates deferred SQL")
    void testDeleteStatementWithParameterPartitionKey() {
        String sql = "DELETE FROM orders WHERE id=?";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("DELETE FROM orders_{deferred}", translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("INSERT with parameter placeholder for partition key - generates deferred SQL")
    void testInsertStatementWithParameterPartitionKey() {
        String sql = "INSERT INTO orders (name, amount, id) VALUES (?, ?, ?)";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO orders_{deferred} (name, amount) VALUES (?, ?)", translator.getRewrittenSql());
        assertEquals(3, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("INSERT with mixed fixed values and parameter placeholders")
    void testInsertStatementWithMixedParameters() {
        String sql = "INSERT INTO orders (name, amount, id) VALUES ('Cindy', ?, ?)";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO orders_{deferred} (name, amount) VALUES ('Cindy', ?)", translator.getRewrittenSql());
        assertEquals(2, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("SELECT with parameter placeholder for partition key - returns unchanged (not supported via processor.rewrite())")
    void testSelectStatementWithParameterPartitionKey() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=? AND name='Alice'";
        String rewritten = processor.rewrite(sql);
        // SELECT returns the id attribute as the original SQL query expects it.
        String expected = "SELECT name, {deferred_key} id, amount FROM orders_{deferred} WHERE name='Alice'";
        assertSqlEquals(expected, rewritten);
    }

    /* DO NOT USE BY NOW
@Test
    @DisplayName("SELECT with parameter placeholder for partition key - returns rewritten via processor.resolve())")
    void testSelectStatementWithParameterPartitionKeyResolve() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=? AND name='Alice'";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("SELECT name, id, amount FROM orders_{deferred} WHERE name='Alice'", translator.getRewrittenSql());
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }
*/

    @Test
    @DisplayName("INNER JOIN ... ON with two partitioned tables and fixed partition key values")
    void testJoinOnWithTwoTablesFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o JOIN users_2 u ON o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("INNER JOIN ... ON with two partitioned tables and deferred partition key parameters")
    void testJoinOnWithTwoTablesDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o JOIN users u ON o.name = u.name AND o.id = ? AND u.id = ?";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, {deferred_key1} id, u.name, {deferred_key2} id FROM orders_{deferred1} o JOIN users_{deferred2} u ON o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("COMMA-style JOIN with two partitioned tables and fixed partition key values")
    void testCommaJoinWithTwoTablesFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o, users u WHERE o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o, users_2 u WHERE o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("resolve() returns translator for JOIN ... ON with two partitioned tables (deferred)")
    void testResolveJoinOnWithTwoTablesDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLStatementTranslator translator = config.resolve(
                "SELECT o.name, o.id, u.name, u.id, u.lastname FROM orders o JOIN users u ON o.name = u.name AND o.id = ? AND u.id = ?");
        assertNotNull(translator, "Translator should not be null for JOIN query");
        String rewritten = translator.getRewrittenSql();
        assertSqlEquals("SELECT o.name, {deferred_key1} id, u.name, {deferred_key2} id, u.lastname FROM orders_{deferred1} o JOIN users_{deferred2} u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("resolve() returns translator for JOIN ... ON WHERE with two partitioned tables (deferred)")
    void testResolveJoinOnWithTwoTablesDeferred2() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLStatementTranslator translator = config.resolve(
                "SELECT o.name, o.id, u.name, u.id, u.lastname FROM orders o JOIN users u ON o.name = u.name WHERE o.id = ? AND u.id = ?");
        assertNotNull(translator, "Translator should not be null for JOIN query");
        String rewritten = translator.getRewrittenSql();
        assertSqlEquals("SELECT o.name, {deferred_key1} id, u.name, {deferred_key2} id, u.lastname FROM orders_{deferred1} o JOIN users_{deferred2} u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("resolve() returns translator for JOIN ... ON with mixed fixed+deferred partition keys")
    void testResolveJoinOnWithMixedFixedDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        // orders has fixed partition key value 1, users has deferred partition key ?
        SQLStatementTranslator translator = config.resolve(
                "SELECT o.name, o.id, u.name, u.id FROM orders o JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = ?");
        assertNotNull(translator, "Translator should not be null for JOIN query");
        String rewritten = translator.getRewrittenSql();
        assertSqlEquals("SELECT o.name, 1 AS id, u.name, {deferred_key2} id FROM orders_1 o JOIN users_{deferred2} u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("COMMA-style JOIN with two partitioned tables and deferred partition key parameters")
    void testCommaJoinWithTwoTablesDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, u.id, u.name, o.id FROM orders o, users u WHERE o.name = u.name AND o.id = ? AND u.id = ?";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, {deferred_key2} id, u.name, {deferred_key1} id FROM orders_{deferred1} o, users_{deferred2} u WHERE o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("COMMA-style JOIN with three tables and one configured table (fixed)")
    void testCommaJoinWithThreeTablesFixed() {
        String sql = "SELECT o.id, u.id, p.id FROM orders o, users u, products p WHERE o.id = u.id AND u.id = p.id AND o.id = 1";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT 1 id, u.id, p.id FROM orders_1 o, users u, products p WHERE 1 = u.id AND u.id = p.id",
                rewritten);
    }

    @Test
    @DisplayName("COMMA-style JOIN with three tables and one configured table (deferred)")
    void testCommaJoinWithThreeTablesDeferred() {
        String sql = "SELECT o.id, u.id, p.id FROM orders o, users u, products p WHERE o.id = u.id AND u.id = p.id AND o.id = ?";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT {deferred_key1} id, u.id, p.id FROM orders_{deferred1} o, users u, products p WHERE {deferred_key1} = u.id AND u.id = p.id",
                rewritten);
    }

    @Test
    @DisplayName("COMMA-style JOIN with three tables and different SELECT attribute order")
    void testCommaJoinWithThreeTablesAttributeOrder() {
        String sql = "SELECT u.id, o.id, p.id FROM users u, orders o, products p WHERE o.id = u.id AND u.id = p.id AND o.id = 1";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT u.id, 1 id, p.id FROM users u, orders_1 o, products p WHERE 1 = u.id AND u.id = p.id",
                rewritten);
    }

    // --- Tests for Fix #3: GROUP BY / ORDER BY / LIMIT / DISTINCT preserved in rewrite() ---

    @Test
    @DisplayName("SELECT with GROUP BY preserves the GROUP BY clause")
    void testSelectWithGroupBy() {
        String sql = "SELECT id, COUNT(name) FROM orders WHERE id=1 GROUP BY id";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT 1 id, COUNT(name) FROM orders_1 GROUP BY id", rewritten);
    }

    @Test
    @DisplayName("SELECT with ORDER BY preserves the ORDER BY clause")
    void testSelectWithOrderBy() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=2 ORDER BY name";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT name, 2 id, amount FROM orders_2 ORDER BY name", rewritten);
    }

    @Test
    @DisplayName("SELECT with Informix FIRST preserves the FIRST clause")
    void testSelectWithFirst() {
        String sql = "SELECT FIRST 10 name, id FROM orders WHERE id=1";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT FIRST 10 name, 1 id FROM orders_1", rewritten);
    }

    @Test
    @DisplayName("SELECT with MySQL LIMIT preserves the LIMIT clause")
    void testSelectWithLimit() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration mysqlConfig = new Configuration(DbType.mysql, props);
        SQLProcessor mysqlProcessor = new SQLProcessor(mysqlConfig);
        String sql = "SELECT name, id FROM orders WHERE id=1 LIMIT 10";
        String rewritten = mysqlProcessor.rewrite(sql);
        assertSqlEquals("SELECT name, 1 id FROM orders_1 LIMIT 10", rewritten);
    }

    @Test
    @DisplayName("SELECT DISTINCT preserves the DISTINCT keyword")
    void testSelectDistinct() {
        String sql = "SELECT DISTINCT name, id FROM orders WHERE id=2";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT DISTINCT name, 2 id FROM orders_2", rewritten);
    }

    @Test
    @DisplayName("SELECT with FOR UPDATE preserves the FOR UPDATE clause")
    void testSelectForUpdate() {
        String sql = "SELECT name, id FROM orders WHERE id=1 FOR UPDATE";
        String rewritten = processor.rewrite(sql);
        assertSqlEquals("SELECT name, 1 id FROM orders_1 FOR UPDATE", rewritten);
    }

    // --- Tests for Fix #1 (outer joins): LEFT / RIGHT / FULL JOIN preserves join type ---

    @Test
    @DisplayName("LEFT JOIN preserves the LEFT JOIN type")
    void testLeftJoinPreservesJoinType() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o LEFT JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o LEFT JOIN users_2 u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("RIGHT JOIN preserves the RIGHT JOIN type")
    void testRightJoinPreservesJoinType() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o RIGHT JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o RIGHT JOIN users_2 u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("FULL JOIN preserves the FULL JOIN type")
    void testFullJoinPreservesJoinType() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o FULL JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o FULL JOIN users_2 u ON o.name = u.name",
                rewritten);
    }

    @Test
    @DisplayName("LEFT JOIN with reversed table order and swapped SELECT attributes")
    void testLeftJoinReversedTableOrder() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT u.name, u.id, o.name, o.id FROM users u LEFT JOIN orders o ON u.name = o.name AND u.id = 2 AND o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT u.name, 2 id, o.name, 1 id FROM users_2 u LEFT JOIN orders_1 o ON u.name = o.name",
                rewritten);
    }

    @Test
    @DisplayName("LEFT JOIN with different SELECT column order (partition key first)")
    void testLeftJoinAttributeOrder() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.id, u.id, o.name, u.name FROM orders o LEFT JOIN users u ON o.name = u.name AND o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT 1 id, 2 id, o.name, u.name FROM orders_1 o LEFT JOIN users_2 u ON o.name = u.name",
                rewritten);
    }

    // --- Tests for single-table JOIN ... ON (only one table configured) ---

    @Test
    @DisplayName("JOIN ... ON with one configured table on the left side (fixed)")
    void testSingleTableJoinOnLeftFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.id, o.name, c.name FROM orders o JOIN customer c ON o.id = c.id AND o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT 1 id, o.name, c.name FROM orders_1 o JOIN customer c ON 1 = c.id",
                rewritten);
    }

    @Test
    @DisplayName("JOIN ... ON with one configured table on the right side (fixed)")
    void testSingleTableJoinOnRightFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT c.name, o.id, o.name FROM customer c JOIN orders o ON c.id = o.id AND o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT c.name, 1 id, o.name FROM customer c JOIN orders_1 o ON c.id = 1",
                rewritten);
    }

    @Test
    @DisplayName("JOIN ... ON with one configured table (deferred)")
    void testSingleTableJoinOnDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.id, o.name, c.name FROM orders o JOIN customer c ON o.id = c.id AND o.id = ?";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT {deferred_key1} id, o.name, c.name FROM orders_{deferred1} o JOIN customer c ON {deferred_key1} = c.id",
                rewritten);
    }

    @Test
    @DisplayName("JOIN ... ON with one configured table and different SELECT attribute order")
    void testSingleTableJoinOnAttributeOrder() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT c.name, o.name, o.id FROM orders o JOIN customer c ON o.id = c.id AND o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT c.name, o.name, 1 id FROM orders_1 o JOIN customer c ON 1 = c.id",
                rewritten);
    }

    @Test
    @DisplayName("LEFT JOIN with one configured table preserves join type")
    void testSingleTableLeftJoin() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.id, o.name, c.name FROM orders o LEFT JOIN customer c ON o.id = c.id AND o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT 1 id, o.name, c.name FROM orders_1 o LEFT JOIN customer c ON 1 = c.id",
                rewritten);
    }

    @Test
    @DisplayName("JOIN ... ON with one configured table and partition key in WHERE clause")
    void testSingleTableJoinOnWhere() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.id, o.name, c.name FROM orders o JOIN customer c ON o.id = c.id WHERE o.id = 1";
        String rewritten = proc.rewrite(sql);
        assertSqlEquals("SELECT 1 id, o.name, c.name FROM orders_1 o JOIN customer c ON 1 = c.id",
                rewritten);
    }

    // --- Tests for Fix #2: JOIN keys in WHERE, handled by rewrite() ---

    @Test
    @DisplayName("JOIN ... ON with partition keys in WHERE clause (fixed) — rewrite path")
    void testRewriteJoinOnWhereFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o JOIN users u ON o.name = u.name WHERE o.id = 1 AND u.id = 2";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o JOIN users_2 u ON o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("JOIN ... ON with partition keys in WHERE clause (deferred) — rewrite path")
    void testRewriteJoinOnWhereDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        String sql = "SELECT o.name, o.id, u.name, u.id FROM orders o JOIN users u ON o.name = u.name WHERE o.id = ? AND u.id = ?";
        String rewritten = proc.rewrite(sql);
        String expected = "SELECT o.name, {deferred_key1} id, u.name, {deferred_key2} id FROM orders_{deferred1} o JOIN users_{deferred2} u ON o.name = u.name";
        assertSqlEquals(expected, rewritten);
    }

    // --- Tests for Fix #1: COMMA join in resolve() path ---

    @Test
    @DisplayName("resolve() returns translator for COMMA JOIN with two tables (fixed)")
    void testResolveCommaJoinTwoTablesFixed() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLStatementTranslator translator = config.resolve(
                "SELECT o.name, o.id, u.name, u.id FROM orders o, users u WHERE o.name = u.name AND o.id = 1 AND u.id = 2");
        assertNotNull(translator, "Translator should not be null for COMMA join");
        assertSqlEquals("SELECT o.name, 1 id, u.name, 2 id FROM orders_1 o, users_2 u WHERE o.name = u.name",
                translator.getRewrittenSql());
    }

    @Test
    @DisplayName("resolve() returns translator for COMMA JOIN with two tables (deferred)")
    void testResolveCommaJoinTwoTablesDeferred() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        Configuration config = new Configuration(DbType.informix, props);
        SQLStatementTranslator translator = config.resolve(
                "SELECT o.name, o.id, u.name, u.id FROM orders o, users u WHERE o.name = u.name AND o.id = ? AND u.id = ?");
        assertNotNull(translator, "Translator should not be null for COMMA join");
        assertSqlEquals("SELECT o.name, {deferred_key1} id, u.name, {deferred_key2} id FROM orders_{deferred1} o, users_{deferred2} u WHERE o.name = u.name",
                translator.getRewrittenSql());
    }

    @Test
    @DisplayName("resolve() returns translator for COMMA JOIN with one table (fixed)")
    void testResolveCommaJoinOneTableFixed() {
        SQLStatementTranslator translator = processor.resolve(
                "SELECT c.customer_id, c.name, o.id, o.name, o.amount FROM orders o, customer c WHERE c.customer_id = o.name AND o.id = 2 AND (o.name='Bob' OR c.address IS NULL)");
        assertNotNull(translator, "Translator should not be null for single-table COMMA join");
        assertSqlEquals("SELECT c.customer_id, c.name, 2 id, o.name, o.amount FROM orders_2 o, customer c WHERE c.customer_id = o.name AND (o.name = 'Bob' OR c.address IS NULL)",
                translator.getRewrittenSql());
    }

    @Test
    @DisplayName("resolve() returns translator for COMMA JOIN with one table (deferred)")
    void testResolveCommaJoinOneTableDeferred() {
        SQLStatementTranslator translator = processor.resolve(
                "SELECT c.customer_id, c.name, o.id, o.name, o.amount FROM orders o, customer c WHERE c.customer_id = o.name AND o.id = ? AND (o.name='Bob' OR c.address IS NULL)");
        assertNotNull(translator, "Translator should not be null for single-table COMMA join");
        assertSqlEquals("SELECT c.customer_id, c.name, {deferred_key} id, o.name, o.amount FROM orders_{deferred} o, customer c WHERE c.customer_id = o.name AND (o.name = 'Bob' OR c.address IS NULL)",
                translator.getRewrittenSql());
    }

    @Test
    @DisplayName("resolve() returns translator for the fixed query")
    void testResolveJoinWith3Tables() {
        String sql = "SELECT c.customer_id, o.id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "AND o.id = 28 "
                + "ORDER BY o.order_date DESC";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null");
        String expected = "SELECT c.customer_id, 28 id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders_28 o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "ORDER BY o.order_date DESC";
        assertSqlEquals(expected, translator.getRewrittenSql());
    }

    @Test
    @DisplayName("resolve() returns translator for 3-table JOIN with deferred partition key")
    void testResolveJoinWith3TablesDeferred() {
        String sql = "SELECT c.customer_id, o.id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "AND o.id = ? "
                + "ORDER BY o.order_date DESC";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null");
        String expected = "SELECT c.customer_id, {deferred_key1} id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders_{deferred1} o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "ORDER BY o.order_date DESC";
        assertSqlEquals(expected, translator.getRewrittenSql());
    }

    @Test
    @DisplayName("3-table JOIN with deferred partition key substitutes placeholders at execution time")
    void testResolveJoinWith3TablesDeferredFinalSql() throws SQLException {
        String sql = "SELECT c.customer_id, o.id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "AND o.id = ? "
                + "ORDER BY o.order_date DESC";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);

        Map<Integer, Object> params = new HashMap<>();
        params.put(1, 28);
        String expected = "SELECT c.customer_id, 28 id, o.order_date, p.product_name "
                + "FROM customers c "
                + "INNER JOIN orders_28 o "
                + "    ON c.customer_id = o.customer_id "
                + "INNER JOIN products p "
                + "    ON o.product_id = p.product_id "
                + "WHERE o.order_date >= '2024-01-01' "
                + "ORDER BY o.order_date DESC";
        assertSqlEquals(expected, translator.getFinalSql(params));
    }

    @Test
    @DisplayName("3-table COMMA JOIN with deferred partition key substitutes placeholders at execution time")
    void testResolveCommaJoinThreeTablesDeferredFinalSql() throws SQLException {
        String sql = "SELECT o.id, u.id, p.id FROM orders o, users u, products p "
                + "WHERE o.id = u.id AND u.id = p.id AND o.id = ?";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator);

        Map<Integer, Object> params = new HashMap<>();
        params.put(1, 42);
        assertSqlEquals("SELECT 42 id, u.id, p.id FROM orders_42 o, users u, products p WHERE 42 = u.id AND u.id = p.id",
                translator.getFinalSql(params));
    }

    @Test
    @DisplayName("rewrite UNION query where one side has a configured table (fixed partition key)")
    void testSelectStatementWithUnion() {
        String sql = "SELECT name, id FROM orders WHERE id = 1 UNION SELECT name, id FROM archive WHERE id = 1";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 1 id FROM orders_1 UNION SELECT name, id FROM archive WHERE id = 1";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("rewrite UNION query where one side has a configured table (deferred partition key)")
    void testSelectStatementWithUnionDeferred() {
        String sql = "SELECT name, id FROM orders WHERE id = ? UNION SELECT name, id FROM archive WHERE id = ?";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, {deferred_key} id FROM orders_{deferred} UNION SELECT name, id FROM archive WHERE id = ?";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("rewrite UNION ALL query with configured table")
    void testSelectStatementWithUnionAll() {
        String sql = "SELECT name, id FROM orders WHERE id = 2 UNION ALL SELECT name, id FROM archive WHERE id = 2";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 2 id FROM orders_2 UNION ALL SELECT name, id FROM archive WHERE id = 2";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("rewrite UNION with both sides using the same configured table with different partition keys")
    void testSelectStatementWithUnionBothSides() {
        String sql = "SELECT name, id FROM orders WHERE id = 1 UNION SELECT name, id FROM orders WHERE id = 2";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 1 id FROM orders_1 UNION SELECT name, 2 id FROM orders_2";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("resolve UNION query with one configured table (fixed partition key)")
    void testResolveUnion() {
        String sql = "SELECT name, id FROM orders WHERE id = 1 UNION SELECT name, id FROM archive WHERE id = 1";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null for UNION query");
        String rewritten = translator.getRewrittenSql();
        String expected = "SELECT name, 1 id FROM orders_1 UNION SELECT name, id FROM archive WHERE id = 1";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("resolve UNION query with one configured table (deferred partition key)")
    void testResolveUnionDeferred() {
        String sql = "SELECT name, id FROM orders WHERE id = ? UNION SELECT name, id FROM archive WHERE id = ?";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null for UNION query");
        String rewritten = translator.getRewrittenSql();
        String expected = "SELECT name, {deferred_key} id FROM orders_{deferred} UNION SELECT name, id FROM archive WHERE id = ?";
        assertSqlEquals(expected, rewritten);
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("rewrite INTERSECT query with configured table")
    void testSelectStatementWithIntersect() {
        String sql = "SELECT name, id FROM orders WHERE id = 1 INTERSECT SELECT name, id FROM archive WHERE id = 1";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 1 id FROM orders_1 INTERSECT SELECT name, id FROM archive WHERE id = 1";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("rewrite EXCEPT query with configured table")
    void testSelectStatementWithExcept() {
        String sql = "SELECT name, id FROM orders WHERE id = 1 EXCEPT SELECT name, id FROM archive WHERE id = 1";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT name, 1 id FROM orders_1 EXCEPT SELECT name, id FROM archive WHERE id = 1";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("CROSS JOIN with fixed partition key in WHERE")
    void testCrossJoinWithFixedPartitionKey() {
        String sql = "SELECT o.id, o.name FROM orders o CROSS JOIN customer c WHERE o.id = 1 AND c.name = 'Alice'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT 1 id, o.name FROM orders_1 o CROSS JOIN customer c WHERE c.name = 'Alice'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("CROSS JOIN with deferred partition key in WHERE")
    void testCrossJoinWithDeferredPartitionKey() {
        String sql = "SELECT o.id, o.name FROM orders o CROSS JOIN customer c WHERE o.id = ? AND c.name = 'Alice'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT {deferred_key} id, o.name FROM orders_{deferred} o CROSS JOIN customer c WHERE c.name = 'Alice'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("bare JOIN (no ON) with fixed partition key in WHERE")
    void testBareJoinWithFixedPartitionKey() {
        String sql = "SELECT o.id, o.name FROM orders o JOIN customer c WHERE o.id = 2 AND c.name = 'Bob'";
        String rewritten = processor.rewrite(sql);
        String expected = "SELECT 2 id, o.name FROM orders_2 o JOIN customer c WHERE c.name = 'Bob'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("resolve CROSS JOIN with fixed partition key")
    void testResolveCrossJoin() {
        String sql = "SELECT o.id, o.name FROM orders o CROSS JOIN customer c WHERE o.id = 1 AND c.name = 'Alice'";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null for CROSS JOIN");
        String rewritten = translator.getRewrittenSql();
        String expected = "SELECT 1 id, o.name FROM orders_1 o CROSS JOIN customer c WHERE c.name = 'Alice'";
        assertSqlEquals(expected, rewritten);
    }

    @Test
    @DisplayName("resolve bare JOIN with deferred partition key")
    void testResolveBareJoinDeferred() {
        String sql = "SELECT o.id, o.name FROM orders o JOIN customer c WHERE o.id = ? AND c.name = 'Alice'";
        SQLStatementTranslator translator = processor.resolve(sql);
        assertNotNull(translator, "Translator should not be null for bare JOIN");
        String rewritten = translator.getRewrittenSql();
        String expected = "SELECT {deferred_key} id, o.name FROM orders_{deferred} o JOIN customer c WHERE c.name = 'Alice'";
        assertSqlEquals(expected, rewritten);
        assertEquals(1, translator.getDeferredPartitionKeyIndex());
    }

    @Test
    @DisplayName("resolve returns the same cached translator for repeated calls")
    void testResolveReturnsSameCachedInstance() {
        String sql = "SELECT o.id, o.name FROM orders o WHERE o.id = 1";
        SQLStatementTranslator first = processor.resolve(sql);
        SQLStatementTranslator second = processor.resolve(sql);
        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    @DisplayName("statements requiring no transformation are cached too")
    void testNoopStatementsAreCached() {
        SQLProcessor proc = new SQLProcessor(new Configuration(DbType.informix, "orders", "key", "id"));
        String sql = "SELECT name, amount FROM orders o WHERE o.name = 'x'";
        assertNull(proc.resolve(sql));
        assertNull(proc.resolve(sql));
        assertEquals(1, proc.estimatedCacheSize());
    }

    @Test
    @DisplayName("cache is bounded by the configured maximum capacity")
    void testMaximumCacheCapacityIsEnforced() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionKey", "id");
        props.setProperty("cache.maximumSize", "3");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        for (int i = 1; i <= 4; i++) {
            proc.resolve("SELECT o.id, o.name FROM orders o WHERE o.id = " + i);
        }
        proc.cleanUpCache();
        assertTrue(proc.estimatedCacheSize() <= 3,
                "cache size " + proc.estimatedCacheSize() + " exceeds configured maximum");
    }

    @Test
    @DisplayName("cache.maximumSize is read from properties, prefixed or not, with a safe default")
    void testCacheSizeConfiguration() {
        Properties prefixed = new Properties();
        prefixed.setProperty("dinuk.cache.maximumSize", "50");
        assertEquals(50, new Configuration(DbType.informix, prefixed).getMaximumCacheSize());

        Properties plain = new Properties();
        plain.setProperty("cache.maximumSize", "25");
        assertEquals(25, new Configuration(DbType.informix, plain).getMaximumCacheSize());

        Properties invalid = new Properties();
        invalid.setProperty("cache.maximumSize", "not-a-number");
        assertEquals(Configuration.DEFAULT_MAXIMUM_CACHE_SIZE,
                new Configuration(DbType.informix, invalid).getMaximumCacheSize());

        assertEquals(Configuration.DEFAULT_MAXIMUM_CACHE_SIZE,
                new Configuration(DbType.informix, new Properties()).getMaximumCacheSize());
    }

    @Test
    @DisplayName("rewrite returns the same cached result for repeated calls")
    void testRewriteReturnsSameCachedResult() {
        String sql = "SELECT o.id, o.name FROM orders o WHERE o.id = 1";
        String first = processor.rewrite(sql);
        String second = processor.rewrite(sql);
        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    @DisplayName("rewrite cache is bounded by the configured maximum capacity")
    void testRewriteCacheCapacityIsEnforced() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionKey", "id");
        props.setProperty("cache.maximumSize", "3");
        Configuration config = new Configuration(DbType.informix, props);
        SQLProcessor proc = new SQLProcessor(config);
        for (int i = 1; i <= 4; i++) {
            proc.rewrite("SELECT o.id, o.name FROM orders o WHERE o.id = " + i);
        }
        proc.cleanUpRewriteCache();
        assertTrue(proc.estimatedRewriteCacheSize() <= 3,
                "rewrite cache size " + proc.estimatedRewriteCacheSize() + " exceeds configured maximum");
    }
}
