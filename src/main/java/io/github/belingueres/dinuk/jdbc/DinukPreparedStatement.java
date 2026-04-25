package io.github.belingueres.dinuk.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DinukPreparedStatement extends DinukDelegateStatement<PreparedStatement> implements PreparedStatement {

    private static final Logger log = LoggerFactory.getLogger(DinukPreparedStatement.class);

    private SQLStatementTranslator translator;

    private List<PreparedStatementAction> parameterValues = new ArrayList<>();
    private Map<Integer, Object> deferredValues = new HashMap<>();
    private Map<Integer, Object> actualParameters = new HashMap<>();
    private Map<Integer, Object> callerValues = new HashMap<>();
    private PrepareStatementFactory factory;

    public DinukPreparedStatement(Configuration configuration, DinukConnection connection,
            SQLStatementTranslator translator, PrepareStatementFactory factory) {
        super(configuration, connection);
        this.translator = translator;
        this.factory = factory;
    }

    String getFinalSql() throws SQLException {
        return translator.getFinalSql(deferredValues);
    }

    Object getFinalParameterValue(int parameterIndex) {
        return actualParameters.get(parameterIndex);
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        prepareAndBindDelegate();
        return this.delegateStmt.executeQuery();
    }

    @Override
    public int executeUpdate() throws SQLException {
        Integer moved = runMoveIfNeeded(translator, callerValues);
        if (moved != null) {
            return moved;
        }
        prepareAndBindDelegate();
        return this.delegateStmt.executeUpdate();
    }

    @Override
    public boolean execute() throws SQLException {
        if (runMoveIfNeeded(translator, callerValues) != null) {
            return false;
        }
        prepareAndBindDelegate();
        return this.delegateStmt.execute();
    }

    private void prepareAndBindDelegate() throws SQLException {
        String rewrittenSql = getFinalSql();
        log.trace("connection hash: {}, executing SQL: {}", connection.hashCode(), rewrittenSql);
        this.delegateStmt = factory.prepare(rewrittenSql);
        for (PreparedStatementAction action : parameterValues) {
            action.apply(this.delegateStmt);
        }
    }

    @Override
    public void close() throws SQLException {
        super.close();
        this.parameterValues.clear();
        this.parameterValues = null;
        this.translator = null;
        this.deferredValues.clear();
        this.deferredValues = null;
        this.actualParameters.clear();
        this.actualParameters = null;
        this.callerValues.clear();
        this.callerValues = null;
    }

    private <T> void setParameter(int parameterIndex, T value, ParameterBinder<T> binder) throws SQLException {
        callerValues.put(parameterIndex, value);
        int finalIndex = getFinalIndex(parameterIndex);
        if (finalIndex == -1) {
            deferredValues.put(parameterIndex, value);
        } else {
            parameterValues.add(ps -> binder.bind(ps, finalIndex, value));
            actualParameters.put(finalIndex, value);
        }
    }

    private int getFinalIndex(int parameterIndex) {
        int removedIndex = translator.getRemovedColumnIndex();
        int deferredIndex = translator.getDeferredPartitionKeyIndex();

        if (translator.isDeferredParameter(parameterIndex)) {
            return -1;
        }

        if (deferredIndex != -1) {
            if (parameterIndex == deferredIndex) {
                return -1;
            }
            return parameterIndex - translator.countDeferredParametersBefore(parameterIndex);
        }

        if (removedIndex != -1 && parameterIndex >= removedIndex) {
            return parameterIndex - 1;
        }

        return parameterIndex;
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setParameter(parameterIndex, sqlType, PreparedStatement::setNull);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setBoolean);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setByte);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setShort);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setInt);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setLong);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setFloat);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setDouble);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setBigDecimal);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setString);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setBytes);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setDate);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setTime);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setTimestamp);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setObject);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setRef);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setBlob);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setClob);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setArray);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setURL);
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setRowId);
    }

    @Override
    public void setNString(int parameterIndex, String x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setNString);
    }

    @Override
    public void setNClob(int parameterIndex, NClob x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setNClob);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        setParameter(parameterIndex, xmlObject, PreparedStatement::setSQLXML);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setAsciiStream);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setParameter(parameterIndex, x, PreparedStatement::setBinaryStream);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setParameter(parameterIndex, reader, PreparedStatement::setCharacterStream);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        setParameter(parameterIndex, reader, PreparedStatement::setNCharacterStream);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setParameter(parameterIndex, reader, PreparedStatement::setClob);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setParameter(parameterIndex, inputStream, PreparedStatement::setBlob);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setParameter(parameterIndex, reader, PreparedStatement::setNClob);
    }

    @Override
    public void clearParameters() throws SQLException {
        this.parameterValues.clear();
        this.deferredValues.clear();
        this.actualParameters.clear();
        this.callerValues.clear();
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setAsciiStream(idx, v, length));
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setUnicodeStream(idx, v, length));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setBinaryStream(idx, v, length));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setObject(idx, v, targetSqlType));
    }

    @Override
    public void addBatch() throws SQLException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        setParameter(parameterIndex, reader, (ps, idx, v) -> ps.setCharacterStream(idx, v, length));
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setDate(idx, v, cal));
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setTime(idx, v, cal));
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setTimestamp(idx, v, cal));
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setParameter(parameterIndex, sqlType, (ps, idx, v) -> ps.setNull(idx, v, typeName));
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        setParameter(parameterIndex, value, (ps, idx, v) -> ps.setNCharacterStream(idx, v, length));
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setParameter(parameterIndex, reader, (ps, idx, v) -> ps.setClob(idx, v, length));
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        setParameter(parameterIndex, inputStream, (ps, idx, v) -> ps.setBlob(idx, v, length));
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setParameter(parameterIndex, reader, (ps, idx, v) -> ps.setNClob(idx, v, length));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setObject(idx, v, targetSqlType, scaleOrLength));
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setAsciiStream(idx, v, length));
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setParameter(parameterIndex, x, (ps, idx, v) -> ps.setBinaryStream(idx, v, length));
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        setParameter(parameterIndex, reader, (ps, idx, v) -> ps.setCharacterStream(idx, v, length));
    }

    @FunctionalInterface
    static interface PreparedStatementAction {
        void apply(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface ParameterBinder<T> {
        void bind(PreparedStatement ps, int parameterIndex, T value) throws SQLException;
    }

}