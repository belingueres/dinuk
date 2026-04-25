/**
 *
 */
package io.github.belingueres.dinuk.jdbc;

import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a plain Statement to intercept execute / executeUpdate / executeQuery
 * for SQL rewriting. Used when the caller uses createStatement() instead of
 * prepareStatement().
 */
public abstract class AbstractDinukStatement<S extends Statement> implements Statement {

    private static final Logger log = LoggerFactory.getLogger(AbstractDinukStatement.class);

	protected final Configuration configuration;
    protected S delegateStmt;

    protected final String rewriteAndLog(String sql) {
        String finalSql = configuration.rewrite(sql);
        log.trace("executing SQL: {}", finalSql);
        return finalSql;
    }

    protected AbstractDinukStatement(Configuration configuration) {
		this.configuration = configuration;
    }

    protected AbstractDinukStatement(Configuration configuration, S delegateStmt) {
        this.configuration = configuration;
        this.delegateStmt = delegateStmt;
    }

}