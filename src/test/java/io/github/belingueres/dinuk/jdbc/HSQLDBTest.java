package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HSQLDBTest {

    static Connection hsqlConn;

    static Properties config;

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        config = loadConfig();
        org.hsqldb.jdbc.JDBCDriver driver = new org.hsqldb.jdbc.JDBCDriver();
        hsqlConn = DriverManager.getConnection("jdbc:hsqldb:mem:testdb", "SA", "");

        // orders tables
        // assume there is a view difined as:
        // CREATE VIEW orders AS
        // SELECT 1 id, name, amount FROM orders_1
        // UNION
        // SELECT 2 id, name, amount FROM orders_2
        // UNION
        // SELECT 3 id, name, amount FROM orders_3
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_1");
            stmt.execute("CREATE TABLE orders_1 (name VARCHAR(100), amount DECIMAL(12,2));");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_2");
            stmt.execute("CREATE TABLE orders_2 (name VARCHAR(100), amount DECIMAL(12,2));");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_3");
            stmt.execute("CREATE TABLE orders_3 (name VARCHAR(100), amount DECIMAL(12,2));");
        }

        // users tables
        // assume there is a view difined as:
        // CREATE VIEW users AS
        // SELECT 1 id, name, lastname FROM users_1
        // UNION
        // SELECT 2 id, name, lastname FROM users_2
        // UNION
        // SELECT 3 id, name, lastname FROM users_3
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users_1");
            stmt.execute("CREATE TABLE users_1 (name VARCHAR(100), lastname VARCHAR(255));");
            stmt.execute("INSERT INTO users_1 (name, lastname) VALUES ('Alice', 'Smith')");
            stmt.execute("INSERT INTO users_1 (name, lastname) VALUES ('Bob', 'Dylan')");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users_2");
            stmt.execute("CREATE TABLE users_2 (name VARCHAR(100), lastname VARCHAR(255));");
            stmt.execute("INSERT INTO users_2 (name, lastname) VALUES ('Alice', 'Cooper')");
            stmt.execute("INSERT INTO users_2 (name, lastname) VALUES ('Bob', 'Smith')");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users_3");
            stmt.execute("CREATE TABLE users_3 (name VARCHAR(100), lastname VARCHAR(255));");
            stmt.execute("INSERT INTO users_3 (name, lastname) VALUES ('Cindy', 'Smith')");
        }

        // range orders tables
        // assume there is a view difined as:
        // CREATE VIEW orders_range AS
        // SELECT id, name, amount FROM orders_range_0
        // UNION
        // SELECT id, name, amount FROM orders_range_1
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_range_0");
            stmt.execute("CREATE TABLE orders_range_0 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_range_0 (id, name, amount) VALUES (5, 'Alice', 1.00)");
            stmt.execute("INSERT INTO orders_range_0 (id, name, amount) VALUES (7, 'Bob', 2.00)");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_range_1");
            stmt.execute("CREATE TABLE orders_range_1 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_range_1 (id, name, amount) VALUES (15, 'Alice', 3.00)");
            stmt.execute("INSERT INTO orders_range_1 (id, name, amount) VALUES (18, 'Bob', 4.00)");
        }

        // list orders tables
        // assume there is a view difined as:
        // CREATE VIEW orders_list AS
        // SELECT region, name, amount FROM orders_list_na
        // UNION
        // SELECT region, name, amount FROM orders_list_eu
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_list_na");
            stmt.execute("CREATE TABLE orders_list_na (region VARCHAR(2) PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_list_na (region, name, amount) VALUES ('us', 'Alice', 1.00)");
            stmt.execute("INSERT INTO orders_list_na (region, name, amount) VALUES ('mx', 'Bob', 2.00)");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_list_eu");
            stmt.execute("CREATE TABLE orders_list_eu (region VARCHAR(2) PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_list_eu (region, name, amount) VALUES ('uk', 'Alice', 3.00)");
            stmt.execute("INSERT INTO orders_list_eu (region, name, amount) VALUES ('ie', 'Bob', 4.00)");
        }

        // hash orders tables
        // assume there is a view difined as:
        // CREATE VIEW orders_hash AS
        // SELECT id, name, amount FROM orders_hash_0
        // UNION
        // SELECT id, name, amount FROM orders_hash_1
        // UNION
        // SELECT id, name, amount FROM orders_hash_2
        // UNION
        // SELECT id, name, amount FROM orders_hash_3
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_hash_0");
            stmt.execute("CREATE TABLE orders_hash_0 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_hash_0 (id, name, amount) VALUES (8, 'Alice', 1.00)");
            stmt.execute("INSERT INTO orders_hash_0 (id, name, amount) VALUES (12, 'Dave', 5.00)");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_hash_1");
            stmt.execute("CREATE TABLE orders_hash_1 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_hash_1 (id, name, amount) VALUES (5, 'Alice', 1.00)");
            stmt.execute("INSERT INTO orders_hash_1 (id, name, amount) VALUES (9, 'Eve', 6.00)");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_hash_2");
            stmt.execute("CREATE TABLE orders_hash_2 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_hash_2 (id, name, amount) VALUES (6, 'Bob', 2.00)");
            stmt.execute("INSERT INTO orders_hash_2 (id, name, amount) VALUES (10, 'Frank', 7.00)");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS orders_hash_3");
            stmt.execute("CREATE TABLE orders_hash_3 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2));");
            stmt.execute("INSERT INTO orders_hash_3 (id, name, amount) VALUES (7, 'Bob', 3.00)");
            stmt.execute("INSERT INTO orders_hash_3 (id, name, amount) VALUES (11, 'Grace', 8.00)");
        }

        DinukDriver.getInstance().shutDownDriver();
    }

    private static Properties loadConfig() throws IOException {
        try (InputStream in = HSQLDBTest.class.getClassLoader().getResourceAsStream("dinuk.properties")) {
            if (in == null) {
                throw new IllegalStateException("dinuk.properties not found on the test classpath");
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        }
    }

    //@BeforeEach
    void cleanTables() throws SQLException {
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM users_1");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM users_2");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM users_3");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM orders_1");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM orders_2");
        }
        try(Statement stmt = hsqlConn.createStatement()) {
            stmt.execute("DELETE FROM orders_3");
        }
    }

    @AfterAll
    static void tearDownBeforeClass() throws SQLException {
        hsqlConn.close();
    }

    @Test
    @Order(1)
    void testInsertPrepared() throws SQLException {
        String sql = "INSERT INTO orders (name, amount, id) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setBigDecimal(2, new BigDecimal("2.34"));
            stmt.setInt(3, 1);
            int count = stmt.executeUpdate();

            assertEquals(1, count);

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_1 WHERE name='Alice'")) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString("name"));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertStatement() throws SQLException {
        String sql = "INSERT INTO orders (name, amount, id) VALUES ('Bob', 2.34, 2)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                Statement stmt = conn.createStatement()) {

            int count = stmt.executeUpdate(sql);
            assertEquals(1, count);

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_2 WHERE name='Bob'")) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString("name"));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertPreparedWithFixedData() throws SQLException {
        String sql = "INSERT INTO orders (name, amount, id) VALUES ('Cindy', ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, new BigDecimal("2.34"));
            stmt.setInt(2, 1);
            int count = stmt.executeUpdate();

            assertEquals(1, count);

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_1 WHERE name='Cindy'")) {
                assertTrue(rs.next());
                assertEquals("Cindy", rs.getString("name"));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertPreparedWithFixedPartitionId() throws SQLException {
        String sql = "INSERT INTO orders (name, amount, id) VALUES (?, ?, 3)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Bob");
            stmt.setBigDecimal(2, new BigDecimal("2.34"));
            int count = stmt.executeUpdate();

            assertEquals(1, count);

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_3 WHERE name='Bob'")) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString("name"));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectStatementWithResultSetTypeConcurrency() throws SQLException {
        String sql = "SELECT name, id, amount from orders where id = 2";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                Statement stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
                ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals("Bob", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(new BigDecimal("2.34"), rs.getBigDecimal(3));
            assertEquals(ResultSet.CONCUR_UPDATABLE, stmt.getResultSetConcurrency());
            assertEquals(ResultSet.TYPE_SCROLL_INSENSITIVE, stmt.getResultSetType());

        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementOneParameter() throws SQLException {
        String sql = "SELECT name, id, amount from orders where id = ? and name='Bob'";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 2);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementOneParameter2() throws SQLException {
        String sql = "SELECT name, id, amount from orders where name=? and id = 2";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Bob");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementTwoParameter() throws SQLException {
        String sql = "SELECT name, id, amount from orders where id = ? and name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 2);
            stmt.setString(2, "Bob");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementTwoParameter2() throws SQLException {
        String sql = "SELECT name, id, amount from orders where name=? and id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Bob");
            stmt.setInt(2, 2);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Bob", rs.getString(1));
                assertEquals(2, rs.getInt(2));
                assertEquals(new BigDecimal("2.34"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementWithJoinTwoPartitionedTables() throws SQLException {
        String sql = "SELECT o.name, o.id, u.name, u.id, u.lastname FROM orders o JOIN users u ON o.name = u.name AND o.id = ? AND u.id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 1); // orders.id=1
            stmt.setInt(2, 2); // users.id=2
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(1, rs.getInt(2));
                assertEquals("Alice", rs.getString(3));
                assertEquals(2, rs.getInt(4));
                assertEquals("Cooper", rs.getString(5));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectPreparedStatementWithJoinTwoPartitionedTables2() throws SQLException {
        String sql = "SELECT o.name, o.id, u.name, u.id, u.lastname FROM orders o JOIN users u ON o.name = u.name WHERE o.id = ? AND u.id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 1); // orders.id=1
            stmt.setInt(2, 2); // users.id=2
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(1, rs.getInt(2));
                assertEquals("Alice", rs.getString(3));
                assertEquals(2, rs.getInt(4));
                assertEquals("Cooper", rs.getString(5));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertRangePrepared() throws SQLException {
        String sql = "INSERT INTO orders_range (name, id, amount) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Diana");
            stmt.setInt(2, 12);
            stmt.setBigDecimal(3, new BigDecimal("5.00"));
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_range_1 WHERE id=12")) {
                assertTrue(rs.next());
                assertEquals("Diana", rs.getString("name"));
                assertEquals(new BigDecimal("5.00"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectRangePrepared() throws SQLException {
        String sql = "SELECT name, id, amount FROM orders_range WHERE id=? AND name='Alice'";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 5);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(5, rs.getInt(2));
                assertEquals(new BigDecimal("1.00"), rs.getBigDecimal(3));
            }
            stmt.clearParameters();
            stmt.setInt(1, 15);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(15, rs.getInt(2));
                assertEquals(new BigDecimal("3.00"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(3)
    void testUpdateRangePrepared() throws SQLException {
        String sql = "UPDATE orders_range SET amount=9.99 WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 7);
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT amount FROM orders_range_0 WHERE id=7")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("9.99"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(3)
    void testDeleteRangePrepared() throws SQLException {
        String sql = "DELETE FROM orders_range WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 18);
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM orders_range_1 WHERE id=18")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertListPrepared() throws SQLException {
        String sql = "INSERT INTO orders_list (name, region, amount) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Nina");
            stmt.setString(2, "ca");
            stmt.setBigDecimal(3, new BigDecimal("5.00"));
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_list_na WHERE region='ca'")) {
                assertTrue(rs.next());
                assertEquals("Nina", rs.getString("name"));
                assertEquals(new BigDecimal("5.00"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectListPrepared() throws SQLException {
        String sql = "SELECT name, region, amount FROM orders_list WHERE region=? AND name='Alice'";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "us");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals("us", rs.getString(2));
                assertEquals(new BigDecimal("1.00"), rs.getBigDecimal(3));
            }
            stmt.clearParameters();
            stmt.setString(1, "uk");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals("uk", rs.getString(2));
                assertEquals(new BigDecimal("3.00"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(3)
    void testUpdateListPrepared() throws SQLException {
        String sql = "UPDATE orders_list SET amount=9.99 WHERE region=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "mx");
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT amount FROM orders_list_na WHERE region='mx'")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("9.99"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(3)
    void testDeleteListPrepared() throws SQLException {
        String sql = "DELETE FROM orders_list WHERE region=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "ie");
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM orders_list_eu WHERE region='ie'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    @Order(1)
    void testInsertHashPrepared() throws SQLException {
        String sql = "INSERT INTO orders_hash (name, id, amount) VALUES (?, ?, ?)";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Hank");
            stmt.setInt(2, 13);
            stmt.setBigDecimal(3, new BigDecimal("5.00"));
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT * FROM orders_hash_1 WHERE id=13")) {
                assertTrue(rs.next());
                assertEquals("Hank", rs.getString("name"));
                assertEquals(new BigDecimal("5.00"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(2)
    void testSelectHashPrepared() throws SQLException {
        String sql = "SELECT name, id, amount FROM orders_hash WHERE id=? AND name='Alice'";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 5);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(5, rs.getInt(2));
                assertEquals(new BigDecimal("1.00"), rs.getBigDecimal(3));
            }
            stmt.clearParameters();
            stmt.setInt(1, 8);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Alice", rs.getString(1));
                assertEquals(8, rs.getInt(2));
                assertEquals(new BigDecimal("1.00"), rs.getBigDecimal(3));
            }
        }
    }

    @Test
    @Order(3)
    void testUpdateHashPrepared() throws SQLException {
        String sql = "UPDATE orders_hash SET amount=9.99 WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 6);
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT amount FROM orders_hash_2 WHERE id=6")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("9.99"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(3)
    void testDeleteHashPrepared() throws SQLException {
        String sql = "DELETE FROM orders_hash WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, 7);
            stmt.setString(2, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM orders_hash_3 WHERE id=7")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Known issue (see AGENTS.md "Known issue"): parameter-index tracking in the
    // visitor scans only the WHERE conditions (findParameterIndexBeforeValue),
    // so a '?' in the UPDATE SET clause shifts the JDBC parameter indices used to
    // resolve a deferred partition key. These tests assert the correct behavior
    // and are @Disabled until the bug is fixed. DELETE has no SET clause, so it is
    // unaffected; the passing test at the end guards the shared path.
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    void testUpdateRangePreparedWithSetParameter() throws SQLException {
        // SET '?' is JDBC param 1; the deferred partition key id is param 2.
        // Correct rewrite: UPDATE orders_range_0 SET amount=? WHERE id=5 AND name='Alice'
        String sql = "UPDATE orders_range SET amount=? WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, new BigDecimal("8.88"));
            stmt.setInt(2, 5);
            stmt.setString(3, "Alice");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT amount FROM orders_range_0 WHERE id=5")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("8.88"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(3)
    void testUpdateKeyPreparedWithSetParameter() throws SQLException {
        // Key mode: correct rewrite is UPDATE orders_2 SET amount=? WHERE name='Bob'.
        // The bug resolves the table from the SET value (9.99) instead of the id (2),
        // producing the invalid table orders_9.99.
        String sql = "UPDATE orders SET amount=? WHERE id=? AND name=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, new BigDecimal("9.99"));
            stmt.setInt(2, 2);
            stmt.setString(3, "Bob");
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT amount FROM orders_2 WHERE name='Bob'")) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal("9.99"), rs.getBigDecimal("amount"));
            }
        }
    }

    @Test
    @Order(3)
    void testDeleteRangePreparedWithPrecedingParameter() throws SQLException {
        // DELETE is unaffected by the known issue: all parameters live in the
        // WHERE clause, so findParameterIndexBeforeValue counts them in order and
        // the deferred partition key index (here 2) is correct.
        String sql = "DELETE FROM orders_range WHERE name=? AND id=?";
        try (Connection conn = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:testdb;user=SA;password=", config);
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "Alice");
            stmt.setInt(2, 5);
            assertEquals(1, stmt.executeUpdate());

            try (Statement verifyStmt = hsqlConn.createStatement();
                    ResultSet rs = verifyStmt.executeQuery("SELECT COUNT(*) FROM orders_range_0 WHERE name='Alice'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}