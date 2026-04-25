package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.druid.DbType;

@DisplayName("INSERT table name rewriting — Druid SQL Parser (no DB required)")
@ExtendWith(MockitoExtension.class)
class PartitionInsertStatementTest {

    @Mock Connection mockConnection;
    @Mock Statement mockStatement;
    @Mock PreparedStatement mockPreparedStatement;

    @Test
    @DisplayName("multi-row INSERT is left unchanged (rows may map to different physical tables)")
    void multiRowInsertUnsupported() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        String sql = "INSERT INTO orders (id, name, amount) VALUES (1, 'Alice', 1.0), (2, 'Bob', 2.0)";
        MockStatement mockStmt = new MockStatement();
        try(DinukDelegateStatement stmt = new DinukDelegateStatement(config, mockStmt)) {
            stmt.executeUpdate(sql);
        }
        String rewrittenSql = mockStmt.getSql();

        assertSqlEquals(sql, rewrittenSql);
        assertNull(config.resolve(sql));
    }

    @Test
    @DisplayName("use numeric value as partition key and rewrite table name with suffix")
    void rewriteByKey() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        String sql = "INSERT INTO orders (id, name, amount) VALUES (1, 'Alice', 2.34)";
        MockStatement mockStmt = new MockStatement();
        try(DinukDelegateStatement stmt = new DinukDelegateStatement(config, mockStmt)) {
            stmt.executeUpdate(sql);
        }
        String rewrittenSql = mockStmt.getSql();

        assertSqlEquals("INSERT INTO orders_1 (name, amount) VALUES ('Alice', 2.34)", rewrittenSql);
    }

    @Test
    @DisplayName("use numeric value as partition key in the middle and rewrite table name with suffix")
    void rewriteByKeyLast() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        String sql = "INSERT INTO orders (name, id, amount) VALUES ('Alice', 26, 2.34)";
        MockStatement mockStmt = new MockStatement();
        try(DinukDelegateStatement stmt = new DinukDelegateStatement(config, mockStmt)) {
            stmt.executeUpdate(sql);
        }
        String rewrittenSql = mockStmt.getSql();

        assertSqlEquals("INSERT INTO orders_26 (name, amount) VALUES ('Alice', 2.34)", rewrittenSql);
    }

    @Test
    @DisplayName("use string value as partition key and rewrite table name with suffix")
    void rewriteByKeyString() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "type_id");

        String sql = "INSERT INTO orders (type_id, name, amount) VALUES ('Alice', 'John', 2.34)";
        MockStatement mockStmt = new MockStatement();
        try(DinukDelegateStatement stmt = new DinukDelegateStatement(config, mockStmt)) {
            stmt.executeUpdate(sql);
        }
        String rewrittenSql = mockStmt.getSql();

        assertSqlEquals("INSERT INTO orders_alice (name, amount) VALUES ('John', 2.34)", rewrittenSql);
    }

    @Test
    @DisplayName("preparedStatement: use numeric value as partition key and rewrite table name with suffix")
    void preparedStatementRewriteByKey() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        String sql = "INSERT INTO orders (id, name, amount) VALUES (?, ?, ?)";
        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                DinukPreparedStatement stmt = (DinukPreparedStatement) conn.prepareStatement(sql)) {
            stmt.setInt(1, 1);
            stmt.setString(2, "Alice");
            stmt.setDouble(3, 2.34);
            String rewrittenSql = stmt.getFinalSql();
            stmt.executeUpdate();

            assertSqlEquals("INSERT INTO orders_1 (name, amount) VALUES (?, ?)", rewrittenSql);
            assertEquals("Alice", stmt.getFinalParameterValue(1));
            assertEquals(2.34, stmt.getFinalParameterValue(2));
        }
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("preparedStatement: use numeric value as partition key and rewrite table name with suffix")
    void preparedStatementRewriteByKeyInTheMiddle() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        String sql = "INSERT INTO orders (name, id, amount) VALUES (?, ?, ?)";
        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                DinukPreparedStatement stmt = (DinukPreparedStatement) conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setInt(2, 1);
            stmt.setDouble(3, 2.34);
            String rewrittenSql = stmt.getFinalSql();
            stmt.executeUpdate();

            assertSqlEquals("INSERT INTO orders_1 (name, amount) VALUES (?, ?)", rewrittenSql);
            assertEquals("Alice", stmt.getFinalParameterValue(1));
            assertEquals(2.34, stmt.getFinalParameterValue(2));
        }
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("preparedStatement: use numeric value as partition key and rewrite table name with suffix")
    void preparedStatementRewriteByKeyLast() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        String sql = "INSERT INTO orders (name, amount, id) VALUES (?, ?, ?)";
        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                DinukPreparedStatement stmt = (DinukPreparedStatement) conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setDouble(2, 2.34);
            stmt.setInt(3, 1);
            String rewrittenSql = stmt.getFinalSql();
            stmt.executeUpdate();

            assertSqlEquals("INSERT INTO orders_1 (name, amount) VALUES (?, ?)", rewrittenSql);
            assertEquals("Alice", stmt.getFinalParameterValue(1));
            assertEquals(2.34, stmt.getFinalParameterValue(2));
        }
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("preparedStatement: INSERT statement unrelated to the partitioned table")
    void preparedStatementNoPartitionedTable() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        String sql = "INSERT INTO employees (name, amount, id) VALUES (?, ?, ?)";
        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setDouble(2, 2.34);
            stmt.setInt(3, 1);
            stmt.executeUpdate();

            assertFalse(stmt instanceof DinukPreparedStatement);
        }
        verify(mockConnection).prepareStatement(sql);
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("preparedStatement with result set type and concurrency: INSERT statement unrelated to the partitioned table")
    void preparedStatementNoPartitionedTable2() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        String sql = "INSERT INTO employees (name, amount, id) VALUES (?, ?, ?)";

        when(mockConnection.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)).thenReturn(mockPreparedStatement);

        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                PreparedStatement stmt = conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setString(1, "Alice");
            stmt.setDouble(2, 2.34);
            stmt.setInt(3, 1);
            stmt.executeUpdate();

            assertFalse(stmt instanceof DinukPreparedStatement);
            // no point in assert that since I have to mock it
            //assertEquals(ResultSet.TYPE_SCROLL_SENSITIVE, stmt.getResultSetType());
            //assertEquals(ResultSet.CONCUR_READ_ONLY, stmt.getResultSetConcurrency());
        }
        verify(mockConnection).prepareStatement(sql, ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    @DisplayName("preparedStatement: INSERT statement using execute()")
    void preparedStatementNoPartitionedTableExecute() throws SQLException {
        Configuration config = new Configuration(DbType.informix, "orders", "key", "id");

        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);

        String sql = "INSERT INTO employees (name, amount, id) VALUES (?, ?, ?)";
        try (DinukConnection conn = new DinukConnection(config, mockConnection);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setDouble(2, 2.34);
            stmt.setInt(3, 1);
            boolean result = stmt.execute();

            assertFalse(stmt instanceof DinukPreparedStatement);
        }
        verify(mockConnection).prepareStatement(sql);
        verify(mockPreparedStatement).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    /*
    @Test
    @DisplayName("preserves column list unchanged")
    void preservesColumnList() {
        String sql      = "INSERT INTO orders (order_id, customer, amount) VALUES (1, 'Bob', 99.9)";
        String rewritten = rewriteInsertTable(sql, DbType.informix, "orders_eu");

        assertSqlEquals("""
                INSERT INTO orders_eu (order_id, customer, amount)
                VALUES (1, 'Bob', 99.9)
                """, rewritten);
    }

    @Test
    @DisplayName("preserves all value literals unchanged")
    void preservesValues() {
        String sql      = "INSERT INTO orders (id, name, amount) VALUES (42, 'Test', 3.14)";
        String rewritten = rewriteInsertTable(sql, DbType.informix, "orders_new");

        assertSqlContains(rewritten, "42");
        assertSqlContains(rewritten, "'Test'");
        assertSqlContains(rewritten, "3.14");
    }

    @Test
    @DisplayName("handles table name with underscore suffix (e.g. year sharding)")
    void yearShardingTableName() {
        String sql      = "INSERT INTO events (id, type) VALUES (1, 'click')";
        String rewritten = rewriteInsertTable(sql, DbType.informix, "events_2024");

        assertAll(
            () -> assertSqlContains(rewritten, "events_2024"),
            () -> assertSqlContains(rewritten, "'click'"),
            () -> assertSqlNotContains(rewritten, "INTO events ")
        );
    }
    */

}
