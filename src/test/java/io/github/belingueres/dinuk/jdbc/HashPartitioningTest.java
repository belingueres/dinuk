package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

class HashPartitioningTest {

    private SQLProcessor hash4;

    private SQLProcessor newProcessor(Properties props) {
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    @BeforeEach
    void setUp() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "hash");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.hash.partitions", "4");
        hash4 = newProcessor(props);
    }

    @Test
    @DisplayName("hash configuration parses the partition count")
    void configParsing() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "hash");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.hash.partitions", "4");
        Configuration config = new Configuration(DbType.informix, props);
        assertEquals(1, config.getTableConfigCount());
        Configuration.TableConfig tableConfig = config.getTableConfig(0);
        assertTrue(tableConfig.isHashType());
        assertTrue(tableConfig.isValueMapped());
        assertEquals(4, tableConfig.getHashPartitions());
    }

    @Test
    @DisplayName("SELECT routes to MOD(value, partitions) and keeps the partition key condition")
    void selectRoutesToModPartition() {
        String sql = "SELECT name, id, amount FROM orders WHERE id=5 AND name='Alice'";
        assertSqlEquals("SELECT name, id, amount FROM orders_1 WHERE id=5 AND name='Alice'", hash4.rewrite(sql));
        assertSqlEquals("SELECT name, id, amount FROM orders_0 WHERE id=8 AND name='Alice'",
                hash4.rewrite("SELECT name, id, amount FROM orders WHERE id=8 AND name='Alice'"));
        assertSqlEquals("SELECT name, id, amount FROM orders_3 WHERE id=7 AND name='Alice'",
                hash4.rewrite("SELECT name, id, amount FROM orders WHERE id=7 AND name='Alice'"));
        assertSqlEquals("SELECT name, id, amount FROM orders_2 WHERE id=6 AND name='Alice'",
                hash4.rewrite("SELECT name, id, amount FROM orders WHERE id=6 AND name='Alice'"));
    }

    @Test
    @DisplayName("INSERT routes to the hash partition and keeps the partition key column")
    void insertRoutesToHashPartition() {
        String sql = "INSERT INTO orders (id, name, amount) VALUES (6, 'Alice', 2.34)";
        assertSqlEquals("INSERT INTO orders_2 (id, name, amount) VALUES (6, 'Alice', 2.34)", hash4.rewrite(sql));
    }

    @Test
    @DisplayName("UPDATE routes to the hash partition and keeps the partition key condition")
    void updateRoutesToHashPartition() {
        String sql = "UPDATE orders SET name='Bob' WHERE id=7";
        assertSqlEquals("UPDATE orders_3 SET name='Bob' WHERE id=7", hash4.rewrite(sql));
    }

    @Test
    @DisplayName("negative values map with floorMod: -1 goes to partition 3")
    void negativeValuesUseFloorMod() {
        String sql = "SELECT name, id FROM orders WHERE id=-1 AND name='A'";
        assertSqlEquals("SELECT name, id FROM orders_3 WHERE id=-1 AND name='A'", hash4.rewrite(sql));
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=-4 AND name='A'",
                hash4.rewrite("SELECT name, id FROM orders WHERE id=-4 AND name='A'"));
    }

    @Test
    @DisplayName("decimal values are truncated toward zero: 5.9 goes to partition 1")
    void decimalValuesTruncated() {
        String sql = "SELECT name, id FROM orders WHERE id=5.9 AND name='A'";
        assertSqlEquals("SELECT name, id FROM orders_1 WHERE id=5.9 AND name='A'", hash4.rewrite(sql));
    }

    @Test
    @DisplayName("non-numeric values match no hash partition and leave the SQL unchanged")
    void nonNumericValueIsUnchanged() {
        String sql = "SELECT name, id FROM orders WHERE id='abc' AND name='A'";
        assertSqlEquals(sql, hash4.rewrite(sql));
        assertNull(hash4.resolve(sql));
    }

    @Test
    @DisplayName("a parameterized partition key defers resolution and picks the table at execution time")
    void deferredSelectResolvesAtRuntime() throws SQLException {
        String sql = "SELECT name, id, amount FROM orders WHERE id=? AND name='Alice'";
        SQLStatementTranslator translator = hash4.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("SELECT name, id, amount FROM {deferred} WHERE id={deferred_key} AND name='Alice'",
                translator.getRewrittenSql());

        assertSqlEquals("SELECT name, id, amount FROM orders_1 WHERE id=5 AND name='Alice'", translator.getFinalSql(5));
        assertSqlEquals("SELECT name, id, amount FROM orders_2 WHERE id=6 AND name='Alice'", translator.getFinalSql(6));
        assertSqlEquals("SELECT name, id, amount FROM orders_3 WHERE id=-1 AND name='Alice'", translator.getFinalSql(-1));
    }

    @Test
    @DisplayName("a parameterized non-numeric value that maps to no partition fails at execution time")
    void deferredNonNumericValueThrows() throws SQLException {
        String sql = "SELECT name, id FROM orders WHERE id=? AND name='Alice'";
        SQLStatementTranslator translator = hash4.resolve(sql);
        assertNotNull(translator);
        assertThrows(SQLException.class, () -> translator.getFinalSql("abc"));
        assertThrows(SQLException.class, () -> translator.getFinalSql("5 OR 1=1"));
        assertThrows(SQLException.class, () -> translator.getFinalSql("5;DROP TABLE x"));
    }

    @Test
    @DisplayName("Deferred INSERT resolves the hash partition at execution time")
    void deferredInsertResolvesAtRuntime() throws SQLException {
        String sql = "INSERT INTO orders (name, id, amount) VALUES ('Alice', ?, 2.34)";
        SQLStatementTranslator translator = hash4.resolve(sql);
        assertNotNull(translator);
        assertSqlEquals("INSERT INTO {deferred} (name, id, amount) VALUES ('Alice', {deferred_key}, 2.34)",
                translator.getRewrittenSql());
        assertSqlEquals("INSERT INTO orders_3 (name, id, amount) VALUES ('Alice', 7, 2.34)", translator.getFinalSql(7));
    }

    @Test
    @DisplayName("mixed hash + key tables in the same query")
    void mixedHashAndKeyJoin() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "hash");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.hash.partitions", "4");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "uid");
        SQLProcessor processor = newProcessor(props);

        String sql = "SELECT u.username, o.amount FROM users u, orders o "
                + "WHERE u.username=o.owner AND u.uid=7 AND o.id=5";
        assertSqlEquals("SELECT u.username, o.amount FROM users_7 u, orders_1 o "
                + "WHERE u.username=o.owner AND o.id=5", processor.rewrite(sql));
    }
}
