package io.github.belingueres.dinuk.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;

@FunctionalInterface
public interface PrepareStatementFactory {
    PreparedStatement prepare(String sql) throws SQLException;
}
