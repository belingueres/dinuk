/**
 *
 */
package io.github.belingueres.dinuk.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

import com.alibaba.druid.DbType;
import com.alibaba.druid.util.JdbcUtils;

/**
 *
 */
public class DinukDriver implements Driver {

	private static final DinukDriver INSTANCE = new DinukDriver();

	private Driver delegateDriver;
	private String delegateDriverClassName;

	static {
		try {
			DriverManager.registerDriver(INSTANCE);
		} catch (SQLException e) {
			throw new IllegalStateException(
					"Could not register DinukDriver with DriverManager", e);
		}
	}

	/**
	 *
	 */
	public DinukDriver() {
		// nothing
	}

	@Override
	public Connection connect(String url, Properties info) throws SQLException {
		String realURL = stripDinuk(url);
		startupDriver(realURL);
		DbType dbType = JdbcUtils.getDbTypeRaw(realURL, JdbcUtils.getDriverClassName(realURL));
		Configuration connConfig = new Configuration(dbType, info);
		Connection connection = delegateDriver.connect(realURL, info);
		if (connection == null) {
			throw new SQLException("Failed to connect using " + delegateDriver.getClass().getName());
		}
		return new DinukConnection(connConfig, connection);
	}

	private String stripDinuk(String url) {
		String stripped = url.replaceFirst(":dinuk", "");
		if (stripped.startsWith("jdbc:jdbc:")) {
			stripped = stripped.substring("jdbc:".length());
		}
		return stripped;
	}

	/**
	 * Ensures the delegate driver for the given (already stripped) URL is
	 * available, creating it on demand. The driver is cached by class so
	 * different database types reuse their own instance, but the target URL is
	 * resolved per connect.
	 */
	private void startupDriver(String realURL) throws SQLException {
		String driverClassName = JdbcUtils.getDriverClassName(realURL);

		if ("org.hsqldb.jdbcDriver".equals(driverClassName)) {
			driverClassName = "org.hsqldb.jdbc.JDBCDriver";
		}

		synchronized (this) {
			if (delegateDriver == null || !driverClassName.equals(delegateDriverClassName)) {
				Driver driver = JdbcUtils.createDriver(driverClassName);

				if (driver == null) {
					throw new SQLException("No suitable driver found for " + realURL);
				}
				delegateDriver = driver;
				delegateDriverClassName = driverClassName;
			}
		}
	}

	@Override
	public boolean acceptsURL(String url) throws SQLException {
		return url != null && url.startsWith("jdbc:dinuk:");
	}

	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
			throws SQLException {
		String realURL = stripDinuk(url);
		startupDriver(realURL);
		return delegateDriver.getPropertyInfo(realURL, info);
	}

	@Override
	public int getMajorVersion() {
		return delegateDriver.getMajorVersion();
	}

	@Override
	public int getMinorVersion() {
		return delegateDriver.getMinorVersion();
	}

	@Override
	public boolean jdbcCompliant() {
		return delegateDriver.jdbcCompliant();
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		return delegateDriver.getParentLogger();
	}

	/**
	 * Test helpers
	 */

	static DinukDriver getInstance() {
	    return INSTANCE;
	}

	void shutDownDriver() {
	    this.delegateDriver = null;
	    this.delegateDriverClassName = null;
	}

}
