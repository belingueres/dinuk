package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.DbType;

/**
 * End-to-end tests for the cross-partition move produced by an UPDATE that
 * changes the partition key. Each test runs on a fresh isolated HSQLDB
 * in-memory schema so the assertions are deterministic regardless of order.
 */
class MoveExecutorTest {

    Connection raw;

    @BeforeEach
    void setUp() throws Exception {
        raw = new org.hsqldb.jdbc.JDBCDriver().connect(
                "jdbc:hsqldb:mem:movetest", userProps("SA", ""));

        try (Statement stmt = raw.createStatement()) {
            // value-mapped list: region kept as a column in the physical tables
            stmt.execute("DROP TABLE IF EXISTS ml_na");
            stmt.execute("DROP TABLE IF EXISTS ml_eu");
            stmt.execute("CREATE TABLE ml_na (id INT PRIMARY KEY, region VARCHAR(2), name VARCHAR(100), amount DECIMAL(12,2))");
            stmt.execute("CREATE TABLE ml_eu (id INT PRIMARY KEY, region VARCHAR(2), name VARCHAR(100), amount DECIMAL(12,2))");
            stmt.execute("INSERT INTO ml_na (id, region, name, amount) VALUES (1, 'us', 'Alice', 1.00)");
            stmt.execute("INSERT INTO ml_eu (id, region, name, amount) VALUES (2, 'uk', 'Bob', 2.00)");
        }
        try (Statement stmt = raw.createStatement()) {
            // value-mapped range: amount kept as the key column
            stmt.execute("DROP TABLE IF EXISTS mr_0");
            stmt.execute("DROP TABLE IF EXISTS mr_1");
            stmt.execute("CREATE TABLE mr_0 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
            stmt.execute("CREATE TABLE mr_1 (id INT PRIMARY KEY, name VARCHAR(100), amount DECIMAL(12,2))");
            stmt.execute("INSERT INTO mr_0 (id, name, amount) VALUES (5, 'Alice', 3.00)");
            stmt.execute("INSERT INTO mr_1 (id, name, amount) VALUES (6, 'Bob', 15.00)");
        }
        try (Statement stmt = raw.createStatement()) {
            // key mode: country column is absent from physical tables
            stmt.execute("DROP TABLE IF EXISTS mk_us");
            stmt.execute("DROP TABLE IF EXISTS mk_de");
            stmt.execute("CREATE TABLE mk_us (id INT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))");
            stmt.execute("CREATE TABLE mk_de (id INT PRIMARY KEY, name VARCHAR(100), note VARCHAR(100))");
            stmt.execute("INSERT INTO mk_us (id, name, note) VALUES (7, 'Alice', 'y/n?')");
        }
        try (Statement stmt = raw.createStatement()) {
            // value-mapped hash: the partition key (id) is hashed into 2 buckets
            stmt.execute("DROP TABLE IF EXISTS mh_0");
            stmt.execute("DROP TABLE IF EXISTS mh_1");
            stmt.execute("CREATE TABLE mh_0 (id INT PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("CREATE TABLE mh_1 (id INT PRIMARY KEY, name VARCHAR(100))");
            stmt.execute("INSERT INTO mh_1 (id, name) VALUES (1, 'Carol')");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (raw != null) {
            raw.close();
        }
    }

    @Test
    void moveListAcrossPartitions() throws SQLException {
        try (Connection conn = dinukConn(listProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ml SET region=? WHERE id=? AND region=?")) {
            ps.setString(1, "uk");
            ps.setInt(2, 1);
            ps.setString(3, "us");
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM ml_eu WHERE id=1", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM ml_na WHERE id=1"));
        }
    }

    @Test
    void moveListSamePartitionIsInPlace() throws SQLException {
        // both values map to the same table (us and ca -> na): no move, id stays.
        try (Connection conn = dinukConn(listProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE ml SET region=? WHERE id=? AND region=?")) {
            ps.setString(1, "ca");
            ps.setInt(2, 1);
            ps.setString(3, "us");
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM ml_na WHERE id=1", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM ml_eu WHERE id=1"));
        }
    }

    @Test
    void moveRangeAcrossPartitions() throws SQLException {
        // amount 3.00 -> mr_0 ; 99.00 -> mr_1.
        try (Connection conn = dinukConn(rangeProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mr SET amount=? WHERE id=? AND amount=?")) {
            ps.setBigDecimal(1, new BigDecimal("99.00"));
            ps.setInt(2, 5);
            ps.setBigDecimal(3, new BigDecimal("3.00"));
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mr_1 WHERE id=5", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mr_0 WHERE id=5"));
        }
    }

    @Test
    void moveKeyModeAcrossPartitions() throws SQLException {
        // key mode drops the country column from the physical table.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country=? WHERE id=? AND country=?")) {
            ps.setString(1, "de");
            ps.setInt(2, 7);
            ps.setString(3, "us");
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveKeyModeAcrossPartitionsWithPartitionKeyConditionFirst() throws SQLException {
        // The partition-key WHERE condition comes BEFORE the other kept
        // condition (reversed from moveKeyModeAcrossPartitions). Regression
        // test: dropping the (earlier) partition-key condition must not shift
        // the caller index computed for the (later) id condition -- it must
        // stay anchored to its true original position, not a position among
        // only the surviving conditions.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country=?, name=? WHERE country=? AND id=?")) {
            ps.setString(1, "de");
            ps.setString(2, "Alice Moved");
            ps.setString(3, "us");
            ps.setInt(4, 7);
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice Moved");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveKeyModeAcrossPartitionsWithTableAlias() throws SQLException {
        // Hibernate/JPA always emits an aliased UPDATE, e.g.
        // "update customers c1_0 set country=?,... where c1_0.country=? and
        // c1_0.id=?" (this is exactly the SQL shape Spring Data JPQL bulk
        // updates produce). Regression test: the retained WHERE conditions
        // must be unqualified to bare column names before being spliced into
        // the move's generated SQL, since neither the INSERT/SELECT/DELETE nor
        // the in-place UPDATE declare any alias for the physical table --
        // otherwise the alias is left dangling and the database can't resolve
        // "c1_0.id".
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk c1_0 SET c1_0.country=?, c1_0.name=? WHERE c1_0.country=? AND c1_0.id=?")) {
            ps.setString(1, "de");
            ps.setString(2, "Alice Moved");
            ps.setString(3, "us");
            ps.setInt(4, 7);
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice Moved");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveKeyModeSamePartitionWithOtherSetColumnsIsInPlace() throws SQLException {
        // country is set to its own current value (no actual move), alongside
        // another column (name), with the partition-key condition BEFORE the
        // other kept condition in WHERE -- the exact shape Spring Data JPQL
        // produces for "UPDATE ... SET c.country = ..., c.name = ... WHERE
        // c.country = ... AND c.id = ...". Regression test: MoveExecutor
        // .executeInPlace used to replay the caller's original SQL text
        // verbatim against the physical table, which still referenced the
        // partition-key column ("country") that key-mode physical tables never
        // have; a related bug also mis-numbered the id parameter once the
        // country condition was dropped ahead of it. Also carries a table
        // alias, matching Hibernate/JPA's generated SQL shape.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk c1_0 SET c1_0.country=?, c1_0.name=? WHERE c1_0.country=? AND c1_0.id=?")) {
            ps.setString(1, "us");
            ps.setString(2, "Alice Updated");
            ps.setString(3, "us");
            ps.setInt(4, 7);
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_us WHERE id=7", "Alice Updated");
        }
    }

    @Test
    void moveHashAcrossPartitions() throws SQLException {
        // id=1 hashes to bucket 1 (mh_1); id=2 hashes to bucket 0 (mh_0).
        try (Connection conn = dinukConn(hashProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mh SET id=? WHERE id=?")) {
            ps.setInt(1, 2);
            ps.setInt(2, 1);
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mh_0 WHERE id=2", "Carol");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mh_1 WHERE id=1"));
        }
    }

    @Test
    void moveWithLiteralKeysAcrossPartitions() throws SQLException {
        // both the new and old partition key values are SQL literals, not bound
        // parameters: exercises the raw-literal table resolution path.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country='de' WHERE id=7 AND country='us'")) {
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveWithLiteralNewKeyAndDeferredOldKey() throws SQLException {
        // mixed: new key is a literal, old key is a bound parameter.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country='de' WHERE id=? AND country=?")) {
            ps.setInt(1, 7);
            ps.setString(2, "us");
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveViaPlainStatementWithLiteralValues() throws SQLException {
        // a plain (non-prepared) Statement with an all-literal move UPDATE.
        try (Connection conn = dinukConn(keyProps());
                Statement stmt = conn.createStatement()) {
            assertEquals(1, stmt.executeUpdate("UPDATE mk SET country='de' WHERE id=7 AND country='us'"));
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
        }
    }

    @Test
    void moveWhereClauseLiteralQuestionMarkIsNotMistakenForParameter() throws SQLException {
        // The extra condition's literal '?' character (inside 'y/n?') must not
        // be miscounted as a bind-parameter placeholder when the move's WHERE
        // clause is renumbered for the generated INSERT/DELETE; a trailing real
        // parameter (name=?) confirms the renumbering still lines up correctly.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country='de' WHERE id=7 AND country='us' AND note='y/n?' AND name=?")) {
            ps.setString(1, "Alice");
            assertEquals(1, ps.executeUpdate());
            assertRow(conn, "SELECT name FROM mk_de WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_us WHERE id=7"));
            assertScalarString(conn, "SELECT note FROM mk_de WHERE id=7", "y/n?");
        }
    }

    @Test
    void moveRejectsInjectionInDeferredNewKeyValue() throws SQLException {
        // A bound "new key" value crafted to break out of the table-name
        // position must be rejected, not spliced into the generated SQL.
        // Regression test: MoveExecutor.resolveTable previously had no
        // isValidTableSuffix guard, unlike every other table-resolution path
        // in this codebase, letting a bound parameter defeat SQL injection
        // defenses and, in a real exploit, exfiltrate an unrelated table's
        // contents into the attacker's own visible partition.
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country=? WHERE id=7 AND country='us'")) {
            ps.setString(1, "de (id, name) SELECT id, name FROM mk_de --");
            assertThrows(SQLException.class, ps::executeUpdate);
            assertRow(conn, "SELECT name FROM mk_us WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_de WHERE id=7"));
        }
    }

    @Test
    void moveRejectsInjectionInDeferredOldKeyValue() throws SQLException {
        // Same guard, exercised via the old (WHERE-side) deferred key value,
        // which is never validated at parse time either (only a literal old
        // key value is checked before execution).
        try (Connection conn = dinukConn(keyProps());
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mk SET country='de' WHERE id=7 AND country=?")) {
            ps.setString(1, "us OR 1=1");
            assertThrows(SQLException.class, ps::executeUpdate);
            assertRow(conn, "SELECT name FROM mk_us WHERE id=7", "Alice");
            assertEquals(0, count(conn, "SELECT COUNT(*) FROM mk_de WHERE id=7"));
        }
    }

    private Connection dinukConn(Properties props) throws SQLException {
        Configuration config = new Configuration(DbType.informix, props);
        return new DinukConnection(config, raw);
    }

    private static Properties listProps() {
        Properties p = new Properties();
        p.setProperty("viewNamePrefix", "ml");
        p.setProperty("partitionType", "list");
        p.setProperty("partitionKey", "region");
        p.setProperty("ml.list.1.values", "us,ca");
        p.setProperty("ml.list.1.table", "ml_na");
        p.setProperty("ml.list.2.values", "uk,ie");
        p.setProperty("ml.list.2.table", "ml_eu");
        return p;
    }

    private static Properties rangeProps() {
        Properties p = new Properties();
        p.setProperty("viewNamePrefix", "mr");
        p.setProperty("partitionType", "range");
        p.setProperty("partitionKey", "amount");
        p.setProperty("mr.range.1.low", "0");
        p.setProperty("mr.range.1.high", "10");
        p.setProperty("mr.range.1.table", "mr_0");
        p.setProperty("mr.range.2.low", "10");
        p.setProperty("mr.range.2.high", "100");
        p.setProperty("mr.range.2.table", "mr_1");
        return p;
    }

    private static Properties hashProps() {
        Properties p = new Properties();
        p.setProperty("viewNamePrefix", "mh");
        p.setProperty("partitionType", "hash");
        p.setProperty("partitionKey", "id");
        p.setProperty("mh.hash.partitions", "2");
        return p;
    }

    private static Properties keyProps() {
        Properties p = new Properties();
        p.setProperty("viewNamePrefix", "mk");
        p.setProperty("partitionType", "key");
        p.setProperty("partitionKey", "country");
        return p;
    }

    private static Properties userProps(String user, String pass) {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", pass);
        return p;
    }

    private static void assertRow(Connection c, String sql, String name) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(name, rs.getString("name"));
        }
    }

    private static void assertScalarString(Connection c, String sql, String expected) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getString(1));
        }
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
