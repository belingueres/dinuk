package io.github.belingueres.dinuk.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

public class DinukDelegateStatement<S extends Statement> extends AbstractDinukStatement<S> {

	protected final DinukConnection connection;

    public DinukDelegateStatement(Configuration configuration, DinukConnection connection) {
    			this(configuration, connection, null);
	}

    public DinukDelegateStatement(Configuration configuration, S delegateStmt) {
        this(configuration, null, delegateStmt);
}

	public DinukDelegateStatement(Configuration configuration, DinukConnection connection, S delegateStmt) {
		super(configuration, delegateStmt);
		this.connection = connection;
	}

	@Override public ResultSet executeQuery(String sql)       throws SQLException { return delegateStmt.executeQuery(rewriteAndLog(sql)); }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        SQLStatementTranslator translator = configuration.resolve(sql);
        Integer moved = runMoveIfNeeded(translator, Collections.emptyMap());
        if (moved != null) {
            return moved;
        }
        return delegateStmt.executeUpdate(rewriteAndLog(sql));
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        SQLStatementTranslator translator = configuration.resolve(sql);
        if (runMoveIfNeeded(translator, Collections.emptyMap()) != null) {
            return false;
        }
        return delegateStmt.execute(rewriteAndLog(sql));
    }

    /**
     * Runs the cross-partition move for a translator marked {@link
     * SQLStatementTranslator#isMove()}, or does nothing when it isn't one.
     *
     * @param translator the resolved statement, checked for {@link
     *         SQLStatementTranslator#isMove()}
     * @param callerParams the caller-supplied JDBC parameter values, keyed by
     *         1-based index, used to resolve any deferred placeholders
     * @return the row count the move reported, or {@code null} when {@code
     *         translator} is not a move (the caller should fall through to its
     *         normal execute path)
     * @throws SQLException if the move's generated SQL fails to execute
     */
    protected final Integer runMoveIfNeeded(SQLStatementTranslator translator, Map<Integer, Object> callerParams)
            throws SQLException {
        if (translator == null || !translator.isMove()) {
            return null;
        }
        return MoveExecutor.execute(connection.getDelegateConnection(), translator.getMoveConfig(), translator,
                callerParams);
    }

    @Override public int       executeUpdate(String sql, int ag) throws SQLException { return delegateStmt.executeUpdate(rewriteAndLog(sql), ag); }
    @Override public int       executeUpdate(String sql, int[] ci) throws SQLException { return delegateStmt.executeUpdate(rewriteAndLog(sql), ci); }
    @Override public int       executeUpdate(String sql, String[] cn) throws SQLException { return delegateStmt.executeUpdate(rewriteAndLog(sql), cn); }
    @Override public boolean   execute(String sql, int ag)    throws SQLException { return delegateStmt.execute(rewriteAndLog(sql), ag); }
    @Override public boolean   execute(String sql, int[] ci)  throws SQLException { return delegateStmt.execute(rewriteAndLog(sql), ci); }
    @Override public boolean   execute(String sql, String[] cn) throws SQLException { return delegateStmt.execute(rewriteAndLog(sql), cn); }

    // --- remaining delegation boilerplate ---
    @Override
    public void close() throws SQLException {
        if (delegateStmt != null) {
            delegateStmt.close();
        }
    }
    @Override public int       getMaxFieldSize()              throws SQLException { return delegateStmt.getMaxFieldSize(); }
    @Override public void      setMaxFieldSize(int max)       throws SQLException { delegateStmt.setMaxFieldSize(max); }
    @Override public int       getMaxRows()                   throws SQLException { return delegateStmt.getMaxRows(); }
    @Override public void      setMaxRows(int max)            throws SQLException { delegateStmt.setMaxRows(max); }
    @Override public void      setEscapeProcessing(boolean e) throws SQLException { delegateStmt.setEscapeProcessing(e); }
    @Override public int       getQueryTimeout()              throws SQLException { return delegateStmt.getQueryTimeout(); }
    @Override public void      setQueryTimeout(int s)         throws SQLException { delegateStmt.setQueryTimeout(s); }
    @Override public void      cancel()                       throws SQLException { delegateStmt.cancel(); }
    @Override public SQLWarning getWarnings()                 throws SQLException { return delegateStmt.getWarnings(); }
    @Override public void      clearWarnings()                throws SQLException { delegateStmt.clearWarnings(); }
    @Override public void      setCursorName(String n)        throws SQLException { delegateStmt.setCursorName(n); }
    @Override public ResultSet getResultSet()                 throws SQLException { return delegateStmt.getResultSet(); }
    @Override public int       getUpdateCount()               throws SQLException { return delegateStmt.getUpdateCount(); }
    @Override public boolean   getMoreResults()               throws SQLException { return delegateStmt.getMoreResults(); }
    @Override public void      setFetchDirection(int d)       throws SQLException { delegateStmt.setFetchDirection(d); }
    @Override public int       getFetchDirection()            throws SQLException { return delegateStmt.getFetchDirection(); }
    @Override public void      setFetchSize(int r)            throws SQLException { delegateStmt.setFetchSize(r); }
    @Override public int       getFetchSize()                 throws SQLException { return delegateStmt.getFetchSize(); }
    @Override public int       getResultSetConcurrency()      throws SQLException { return delegateStmt.getResultSetConcurrency(); }
    @Override public int       getResultSetType()             throws SQLException { return delegateStmt.getResultSetType(); }
    @Override public void      addBatch(String sql)           throws SQLException { delegateStmt.addBatch(rewriteAndLog(sql)); }
    @Override public void      clearBatch()                   throws SQLException { delegateStmt.clearBatch(); }
    @Override public int[]     executeBatch()                 throws SQLException { return delegateStmt.executeBatch(); }
    @Override public Connection getConnection()               throws SQLException { return delegateStmt.getConnection(); }
    @Override public boolean   getMoreResults(int c)          throws SQLException { return delegateStmt.getMoreResults(c); }
    @Override public ResultSet getGeneratedKeys()             throws SQLException { return delegateStmt.getGeneratedKeys(); }
    @Override public int       getResultSetHoldability()      throws SQLException { return delegateStmt.getResultSetHoldability(); }
    @Override public boolean   isClosed()                     throws SQLException { return delegateStmt.isClosed(); }
    @Override public void      setPoolable(boolean p)         throws SQLException { delegateStmt.setPoolable(p); }
    @Override public boolean   isPoolable()                   throws SQLException { return delegateStmt.isPoolable(); }
    @Override public void      closeOnCompletion()            throws SQLException { delegateStmt.closeOnCompletion(); }
    @Override public boolean   isCloseOnCompletion()          throws SQLException { return delegateStmt.isCloseOnCompletion(); }
    @Override public <T> T     unwrap(Class<T> i)             throws SQLException { return delegateStmt.unwrap(i); }
    @Override public boolean   isWrapperFor(Class<?> i)       throws SQLException { return delegateStmt.isWrapperFor(i); }

}
