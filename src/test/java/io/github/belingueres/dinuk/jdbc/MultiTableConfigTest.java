package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

class MultiTableConfigTest {

    private SQLProcessor mixedTableTypes() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "0");
        props.setProperty("orders.range.1.high", "10");
        props.setProperty("orders.range.1.table", "orders_0");
        props.setProperty("orders.range.2.low", "11");
        props.setProperty("orders.range.2.high", "20");
        props.setProperty("orders.range.2.table", "orders_1");

        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "uid");

        props.setProperty("viewNamePrefix3", "audit");
        props.setProperty("partitionType3", "range");
        props.setProperty("partitionKey3", "year");
        props.setProperty("audit.range.1.high", "2023");
        props.setProperty("audit.range.1.table", "audit_2023");
        props.setProperty("audit.range.2.low", "2024");
        props.setProperty("audit.range.2.table", "audit_2024");

        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("multiple range tables in the same configuration route independently")
    void multipleRangeTables() {
        SQLProcessor processor = mixedTableTypes();
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=5 AND name='A'",
                processor.rewrite("SELECT name, id FROM orders WHERE id=5 AND name='A'"));
        assertSqlEquals("SELECT y, year FROM audit_2024 WHERE year=2025",
                processor.rewrite("SELECT y, year FROM audit WHERE year=2025"));
    }

    @Test
    @DisplayName("key table routes to its own physical table and uses its own partition key alias")
    void keyTable() {
        SQLProcessor processor = mixedTableTypes();
        assertSqlEquals("SELECT 7 uid, username FROM users_7 u WHERE username='alice'",
                processor.rewrite("SELECT u.uid, username FROM users u WHERE u.uid=7 AND username='alice'"));
    }

    @Test
    @DisplayName("mixed key + range tables in the same query")
    void mixedTypesJoin() {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT u.username, o.amount FROM users u, orders o WHERE u.username=o.owner AND u.uid=7 AND o.id=5";
        assertSqlEquals("SELECT u.username, o.amount FROM users_7 u, orders_0 o WHERE u.username=o.owner AND o.id=5",
                processor.rewrite(sql));
    }

    @Test
    @DisplayName("bare asterisk on a range table performs no translation")
    void rangeTableAsterisk() {
        SQLProcessor processor = mixedTableTypes();
        assertSqlEquals("SELECT * FROM orders WHERE id=5",
                processor.rewrite("SELECT * FROM orders WHERE id=5"));
        assertSqlEquals("SELECT * FROM audit WHERE year=2025",
                processor.rewrite("SELECT * FROM audit WHERE year=2025"));
    }

    @Test
    @DisplayName("bare asterisk on a key table performs no translation")
    void keyTableAsterisk() {
        SQLProcessor processor = mixedTableTypes();
        assertSqlEquals("SELECT * FROM users WHERE u.uid=7",
                processor.rewrite("SELECT * FROM users WHERE u.uid=7"));
    }

    @Test
    @DisplayName("qualified wildcard also disables translation")
    void qualifiedWildcard() {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT u.username, o.* FROM orders o, users u WHERE o.owner=u.username AND o.id=5";
        assertSqlEquals(sql, processor.rewrite(sql));
    }

    @Test
    @DisplayName("range table with a parameter defers resolution to execution time")
    void rangeTableParameter() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT name, id, amount FROM orders WHERE id=? AND name='Alice'";
        assertSqlEquals("SELECT name, id, amount FROM {deferred} WHERE id={deferred_key} AND name='Alice'",
                processor.rewrite(sql));
        assertSqlEquals("SELECT name, id, amount FROM orders_0 WHERE id=5 AND name='Alice'",
                processor.resolve(sql).getFinalSql(5));
    }

    @Test
    @DisplayName("key table with a parameter defers resolution to execution time")
    void keyTableParameter() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT u.uid, username FROM users u WHERE u.uid=? AND username='alice'";
        assertSqlEquals("SELECT {deferred_key} uid, username FROM users_{deferred} u WHERE username='alice'",
                processor.rewrite(sql));
        assertSqlEquals("SELECT 7 uid, username FROM users_7 u WHERE username='alice'",
                processor.resolve(sql).getFinalSql(7));
    }

    @Test
    @DisplayName("key table deferred suffix is lowercased like the fixed-value path")
    void keyTableDeferredSuffixLowercased() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String deferred = "SELECT u.uid, username FROM users u WHERE u.uid=? AND username='alice'";
        assertSqlEquals("SELECT 'US' uid, username FROM users_us u WHERE username='alice'",
                processor.resolve(deferred).getFinalSql("US"));
    }

    @Test
    @DisplayName("key table fixed and deferred values route to the same lowercased physical table")
    void keyTableFixedAndDeferredRouteToSameTable() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String fixed = "SELECT u.uid, username FROM users u WHERE u.uid='US' AND username='alice'";
        String deferred = "SELECT u.uid, username FROM users u WHERE u.uid=? AND username='alice'";
        assertSqlEquals(processor.rewrite(fixed),
                processor.resolve(deferred).getFinalSql("US"));
    }

    @Test
    @DisplayName("key table multi-table deferred suffix is lowercased like the fixed-value path")
    void keyTableMultiTableDeferredSuffixLowercased() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT u.username, o.amount FROM users u, orders o "
                + "WHERE u.username=o.owner AND u.uid=? AND o.id=?";
        assertSqlEquals("SELECT u.username, o.amount FROM users_us u, orders_0 o WHERE u.username=o.owner AND o.id=5",
                processor.resolve(sql).getFinalSql(keyTableMultiTableParams()));
    }

    private static Map<Integer, Object> keyTableMultiTableParams() {
        Map<Integer, Object> params = new HashMap<>();
        params.put(1, "US");
        params.put(2, 5);
        return params;
    }

    @Test
    @DisplayName("mixed key + range tables with parameters defer resolution")
    void mixedParameterJoin() throws Exception {
        SQLProcessor processor = mixedTableTypes();
        String sql = "SELECT u.username, o.amount FROM users u, orders o WHERE u.username=o.owner AND u.uid=? AND o.id=?";
        assertSqlEquals("SELECT u.username, o.amount FROM users_{deferred1} u, {deferred2} o "
                        + "WHERE u.username=o.owner AND o.id={deferred_key2}",
                processor.rewrite(sql));
        Map<Integer, Object> params = new HashMap<>();
        params.put(1, 7);
        params.put(2, 5);
        assertSqlEquals("SELECT u.username, o.amount FROM users_7 u, orders_0 o WHERE u.username=o.owner AND o.id=5",
                processor.resolve(sql).getFinalSql(params));
    }
}