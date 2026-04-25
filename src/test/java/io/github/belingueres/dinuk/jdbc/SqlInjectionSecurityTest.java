package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;

class SqlInjectionSecurityTest {

    private SQLProcessor stringRangeProcessor() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "A");
        props.setProperty("orders.range.1.high", "M");
        props.setProperty("orders.range.1.table", "orders_am");
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    private SQLProcessor keyProcessor() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    private SQLProcessor numericRangeProcessor() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "0");
        props.setProperty("orders.range.1.high", "9");
        props.setProperty("orders.range.1.table", "orders_0");
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    private SQLProcessor listProcessor() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "list");
        props.setProperty("partitionKey", "region");
        props.setProperty("orders.list.1.values", "us,ca,mx");
        props.setProperty("orders.list.1.table", "orders_na");
        return new SQLProcessor(new Configuration(DbType.informix, props));
    }

    @Test
    @DisplayName("fixed literal values are emitted by Druid with proper quoting")
    void fixedLiteral() {
        SQLProcessor p = keyProcessor();
        assertSqlEquals("SELECT name, 'C' id FROM orders_c WHERE name='A'",
                p.rewrite("SELECT name, id FROM orders WHERE id='C' AND name='A'"));
    }

    @Test
    @DisplayName("key-mode deferred value is a validated table suffix")
    void keyDeferredSuffixValidation() throws Exception {
        SQLProcessor p = keyProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        assertSqlEquals("SELECT name, 5 id FROM orders_5 WHERE name='Alice'", t.getFinalSql(5));
        assertSqlEquals("SELECT name, 'alice' id FROM orders_alice WHERE name='Alice'", t.getFinalSql("alice"));
        assertThrows(SQLException.class, () -> t.getFinalSql("5 OR 1=1"));
        assertThrows(SQLException.class, () -> t.getFinalSql("5; DROP TABLE x"));
    }

    @Test
    @DisplayName("range-mode deferred value matching a string range is escaped, not injected")
    void rangeDeferredEscaping() throws Exception {
        SQLProcessor p = stringRangeProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        assertSqlEquals("SELECT name, id FROM orders_am WHERE id='C' AND name='Alice'", t.getFinalSql("C"));
        assertSqlEquals("SELECT name, id FROM orders_am "
                        + "WHERE id='A'' OR ''1''=''1' AND name='Alice'",
                t.getFinalSql("A' OR '1'='1"));
    }

    @Test
    @DisplayName("numeric range: injection payloads that pass the range check are escaped, not injected")
    void numericRangeDeferredEscaping() throws Exception {
        SQLProcessor p = numericRangeProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=5 AND name='Alice'", t.getFinalSql(5));
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id='5 OR 1=1' AND name='Alice'",
                t.getFinalSql("5 OR 1=1"));
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id='5;DROP TABLE x' AND name='Alice'",
                t.getFinalSql("5;DROP TABLE x"));
    }

    @Test
    @DisplayName("list-mode deferred values that match no list are rejected, not injected")
    void listDeferredUnmatchedValuesThrow() throws Exception {
        SQLProcessor p = listProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, region FROM orders WHERE region=? AND name='Alice'");
        assertThrows(SQLException.class, () -> t.getFinalSql("' OR '1'='1"));
        assertThrows(SQLException.class, () -> t.getFinalSql("5;DROP TABLE x"));
    }

    @Test
    @DisplayName("key-mode fixed literal values with unsafe characters are not rewritten")
    void keyFixedUnsafeValuesNotRewritten() {
        SQLProcessor p = keyProcessor();
        String[] payloads = {
                "c; DROP TABLE x",
                "c FROM orders",
                "1 OR 1=1",
                "a-b",
                "x'' OR ''1''=''1",
                "1+1"
        };
        for (String payload : payloads) {
            String sql = "SELECT name, id FROM orders WHERE id='" + payload + "' AND name='Alice'";
            assertSqlEquals(sql, p.rewrite(sql), "payload: " + payload);
        }
    }

    @Test
    @DisplayName("key-mode fixed expression partition values are not rewritten")
    void keyFixedExpressionNotRewritten() {
        SQLProcessor p = keyProcessor();
        String sql = "SELECT name, id FROM orders WHERE id=1+1 AND name='Alice'";
        assertSqlEquals(sql, p.rewrite(sql));
    }

    @Test
    @DisplayName("key-mode fixed unsafe values resolve to no translation")
    void keyFixedUnsafeResolveReturnsNull() {
        SQLProcessor p = keyProcessor();
        String sql = "SELECT name, id FROM orders WHERE id='c; DROP TABLE x' AND name='Alice'";
        assertNull(p.resolve(sql));
    }

    @Test
    @DisplayName("SQL whose literals contain a placeholder token is refused, not corrupted")
    void placeholderTokenInLiteralNotCorrupted() {
        SQLProcessor p = numericRangeProcessor();
        String sql = "SELECT name, id FROM orders WHERE id=? AND name='{deferred_key}'";
        assertNull(p.resolve(sql), "statement should be refused instead of corrupting the literal");
        assertSqlEquals(sql, p.rewrite(sql));
    }

    @Test
    @DisplayName("key-mode SQL whose literals contain a placeholder token is refused, not corrupted")
    void keyModePlaceholderTokenInLiteralNotCorrupted() {
        SQLProcessor p = keyProcessor();
        String sql = "SELECT name, id FROM orders WHERE id=? AND name='{deferred}'";
        assertNull(p.resolve(sql), "statement should be refused instead of corrupting the literal");
        assertSqlEquals(sql, p.rewrite(sql));
    }

    @Test
    @DisplayName("multi-table SQL whose literals contain a placeholder token is refused, not corrupted")
    void multiTablePlaceholderTokenInLiteralNotCorrupted() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "key");
        props.setProperty("partitionKey", "id");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "key");
        props.setProperty("partitionKey2", "id");
        SQLProcessor p = new SQLProcessor(new Configuration(DbType.informix, props));
        String sql = "SELECT o.id, u.id FROM orders o, users u "
                + "WHERE o.name='{deferred_key1}' AND o.id=? AND u.id=?";
        assertNull(p.resolve(sql), "statement should be refused instead of corrupting the literal");
    }

    @Test
    @DisplayName("range-mode deferred values that fit no range are rejected too, matching list-mode behavior")
    void rangeDeferredUnmatchedValueThrows() throws Exception {
        SQLProcessor p = numericRangeProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        assertThrows(SQLException.class, () -> t.getFinalSql("Z OR 1=1"));
        assertThrows(SQLException.class, () -> t.getFinalSql("100"));
    }

    @Test
    @DisplayName("custom Number subclass with a hostile toString is quoted, not spliced raw")
    void customNumberSubclassToStringIsQuoted() throws Exception {
        SQLProcessor p = numericRangeProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        Number malicious = new Number() {
            @Override public int intValue() { return 5; }
            @Override public long longValue() { return 5L; }
            @Override public float floatValue() { return 5f; }
            @Override public double doubleValue() { return 5d; }
            @Override public String toString() { return "5) OR 1=1 --"; }
        };
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id='5) OR 1=1 --' AND name='Alice'",
                t.getFinalSql(malicious));
    }

    @Test
    @DisplayName("standard-library numeric types stay unquoted in deferred literals")
    void standardNumberTypesStayUnquoted() throws Exception {
        SQLProcessor p = numericRangeProcessor();
        SQLStatementTranslator t = p.resolve("SELECT name, id FROM orders WHERE id=? AND name='Alice'");
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=5 AND name='Alice'",
                t.getFinalSql(new java.math.BigDecimal("5")));
        assertSqlEquals("SELECT name, id FROM orders_0 WHERE id=7 AND name='Alice'",
                t.getFinalSql(new java.util.concurrent.atomic.AtomicInteger(7)));
    }
}