package io.github.belingueres.dinuk.jdbc;

import static io.github.belingueres.dinuk.jdbc.TestHelper.assertSqlEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.druid.DbType;

@DisplayName("deferred partition key parameter binding (no DB required)")
@ExtendWith(MockitoExtension.class)
class DeferredParameterBindingTest {

    @Mock Connection mockConnection;

    private Configuration twoTableRangeConfig() {
        Properties props = new Properties();
        props.setProperty("viewNamePrefix", "orders");
        props.setProperty("partitionType", "range");
        props.setProperty("partitionKey", "id");
        props.setProperty("orders.range.1.low", "0");
        props.setProperty("orders.range.1.high", "99");
        props.setProperty("orders.range.1.table", "orders_0");
        props.setProperty("viewNamePrefix2", "users");
        props.setProperty("partitionType2", "range");
        props.setProperty("partitionKey2", "id");
        props.setProperty("users.range.1.low", "0");
        props.setProperty("users.range.1.high", "99");
        props.setProperty("users.range.1.table", "users_0");
        return new Configuration(DbType.informix, props);
    }

    @Test
    @DisplayName("two deferred partition keys shift a trailing normal parameter to its final index")
    void twoDeferredKeysShiftTrailingParameter() throws SQLException {
        String sql = "SELECT o.name, u.name FROM orders o JOIN users u "
                + "ON o.user_id = u.id WHERE o.id = ? AND u.id = ? AND u.name = ?";
        try (DinukConnection conn = new DinukConnection(twoTableRangeConfig(), mockConnection);
                DinukPreparedStatement stmt = (DinukPreparedStatement) conn.prepareStatement(sql)) {
            stmt.setInt(1, 5);
            stmt.setInt(2, 6);
            stmt.setString(3, "Bob");

            String finalSql = stmt.getFinalSql();
            assertSqlEquals("SELECT o.name, u.name FROM orders_0 o JOIN users_0 u "
                    + "ON o.user_id = u.id WHERE o.id = 5 AND u.id = 6 AND u.name = ?",
                    finalSql);
            assertEquals("Bob", stmt.getFinalParameterValue(1));
            assertNull(stmt.getFinalParameterValue(2));
        }
    }

    @Test
    @DisplayName("non-contiguous deferred keys shift each normal parameter independently")
    void nonContiguousDeferredKeys() throws SQLException {
        String sql = "SELECT o.name, u.name FROM orders o JOIN users u "
                + "ON o.user_id = u.id WHERE u.name = ? AND o.id = ? AND u.id = ? AND o.amount = ?";
        try (DinukConnection conn = new DinukConnection(twoTableRangeConfig(), mockConnection);
                DinukPreparedStatement stmt = (DinukPreparedStatement) conn.prepareStatement(sql)) {
            stmt.setString(1, "Bob");
            stmt.setInt(2, 5);
            stmt.setInt(3, 6);
            stmt.setString(4, "high");

            String finalSql = stmt.getFinalSql();
            assertSqlEquals("SELECT o.name, u.name FROM orders_0 o JOIN users_0 u "
                    + "ON o.user_id = u.id WHERE u.name = ? AND o.id = 5 AND u.id = 6 AND o.amount = ?",
                    finalSql);
            assertEquals("Bob", stmt.getFinalParameterValue(1));
            assertEquals("high", stmt.getFinalParameterValue(2));
            assertNull(stmt.getFinalParameterValue(3));
        }
    }
}