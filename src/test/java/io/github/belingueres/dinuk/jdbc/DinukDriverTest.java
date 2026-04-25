package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DinukDriverTest {

    @BeforeAll
    static void setUpBeforeClass() throws Exception {
        // force registration
        //Class.forName("io.github.belingueres.dinuk.jdbc.DinukDriver");
    }

    @BeforeEach
    void setUp() throws Exception {
    }

    @AfterEach
    void tearDown() {
        DinukDriver.getInstance().shutDownDriver();
    }

    @Test
    @DisplayName("Test driver is registered")
    void testDriverIsRegistered() {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        boolean found = false;
        while(drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver instanceof DinukDriver) {
                found = true;
                break;
            }
        }
        assertTrue(found, "DinukDriver not registered");
    }

    @Test
    @DisplayName("Test the getDriver() with prefix jdbc:dinuk")
    void testGetDriver() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:dinuk:xxxx");
        assertTrue(driver instanceof DinukDriver, "Driver instance type is incorrect: " + driver.getClass().getName());
    }

    @Test
    @DisplayName("Test the getDriver() with prefix jdbc:hsql")
    void testGetDriverOtherPrefix() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:hsqldb:mem:testdb");
        assertFalse(driver instanceof DinukDriver, "Driver instance type is incorrect: " + driver.getClass().getName());
    }

    @Test
    @DisplayName("Connections to different dinuk URLs target their own database")
    void testMultipleDatabasesGetOwnConnection() throws SQLException {
        DinukDriver.getInstance().shutDownDriver();
        try (Connection conn1 = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:dinuk_sec_db1;user=SA;password=");
                Connection conn2 = DriverManager.getConnection("jdbc:dinuk:hsqldb:mem:dinuk_sec_db2;user=SA;password=")) {
            String url1 = conn1.getMetaData().getURL();
            String url2 = conn2.getMetaData().getURL();
            assertTrue(url1.contains("dinuk_sec_db1"), "conn1 URL: " + url1);
            assertTrue(url2.contains("dinuk_sec_db2"), "conn2 URL: " + url2);
            assertFalse(url1.equals(url2), "both connections dialed the same database: " + url1);
        }
    }

    @Test
    @DisplayName("getPropertyInfo forwards the real URL to the delegate driver")
    void testGetPropertyInfoForwardsRealUrl() throws SQLException {
        DriverPropertyInfo[] props = DinukDriver.getInstance()
                .getPropertyInfo("jdbc:dinuk:hsqldb:mem:x", new Properties());
        assertTrue(propertyNames(props).containsAll(
                Arrays.asList("user", "password", "shutdown")),
                "delegate did not recognize the real URL: " + propertyNames(props));
    }

    @Test
    @DisplayName("Double jdbc: prefix in the real URL is normalized on connect")
    void testDoubleJdbcPrefixConnect() throws SQLException {
        DinukDriver.getInstance().shutDownDriver();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:dinuk:jdbc:hsqldb:mem:dinuk_norm_db;user=SA;password=")) {
            String url = conn.getMetaData().getURL();
            assertTrue(url.startsWith("jdbc:hsqldb:"), "URL: " + url);
            assertFalse(url.startsWith("jdbc:jdbc:"), "URL: " + url);
            assertTrue(url.contains("dinuk_norm_db"), "URL: " + url);
        }
    }

    @Test
    @DisplayName("getPropertyInfo also normalizes the double jdbc: prefix")
    void testGetPropertyInfoDoubleJdbcPrefix() throws SQLException {
        DriverPropertyInfo[] props = DinukDriver.getInstance()
                .getPropertyInfo("jdbc:dinuk:jdbc:hsqldb:mem:x", new Properties());
        assertTrue(propertyNames(props).containsAll(
                Arrays.asList("user", "password", "shutdown")),
                "delegate did not recognize the real URL: " + propertyNames(props));
    }

    private static Set<String> propertyNames(DriverPropertyInfo[] props) {
        Set<String> names = new HashSet<>();
        for (DriverPropertyInfo prop : props) {
            names.add(prop.name);
        }
        return names;
    }

}
