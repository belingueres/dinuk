package io.github.belingueres.dinuk.jdbc;

import java.util.List;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class SQLProcessor {

    /**
     * Marker cached in place of {@code null} so that statements requiring no
     * transformation are still cached and skip re-parsing on subsequent calls.
     */
    private static final SQLStatementTranslator NO_TRANSLATION = new SQLStatementTranslator();

    private Configuration configuration;

    private final Cache<String, SQLStatementTranslator> cache;
    private final Cache<String, String> rewriteCache;

    public SQLProcessor(Configuration configuration) {
        this.configuration = configuration;
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(configuration.getMaximumCacheSize());
        this.cache = builder.build();
        this.rewriteCache = builder.build();
    }

    public SQLStatementTranslator resolve(String sql) {
        SQLStatementTranslator translator = cache.get(sql, this::resolveOrNoop);
        return translator == NO_TRANSLATION ? null : translator;
    }

    private SQLStatementTranslator resolveOrNoop(String sql) {
        if (SQLStatementTranslator.containsPlaceholderToken(sql)) {
            return NO_TRANSLATION;
        }
        SQLStatementTranslator translator = translate(sql);
        return translator != null ? translator : NO_TRANSLATION;
    }

    public String rewrite(String sql) {
        return rewriteCache.get(sql, this::rewriteUncached);
    }

    private String rewriteUncached(String sql) {
        if (SQLStatementTranslator.containsPlaceholderToken(sql)) {
            return sql;
        }

        SQLStatement statement = SQLUtils.parseSingleStatement(sql, configuration.getDbType());

        // Check for asterisk in SELECT - if present, don't rewrite
        if (statement instanceof SQLSelectStatement) {
            SQLSelectStatement selectStmt = (SQLSelectStatement) statement;
            if (selectStmt.getSelect() != null && selectStmt.getSelect().getQuery() instanceof SQLSelectQueryBlock) {
                SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) selectStmt.getSelect().getQuery();
                List<SQLSelectItem> selectItems = queryBlock.getSelectList();
                if (selectItems != null && !selectItems.isEmpty() && hasAsterisk(selectItems)) {
                    return sql;
                }
            }
        }

        SQLStatementTranslator translator = translate(statement);
        return translator != null ? translator.getRewrittenSql() : sql;
    }

    private boolean hasAsterisk(List<SQLSelectItem> selectItems) {
        for (SQLSelectItem item : selectItems) {
            if (item.getExpr() instanceof SQLAllColumnExpr) {
                return true;
            }
        }
        return false;
    }

    SQLStatementTranslator translate(String sql) {
        SQLStatement stmt = SQLUtils.parseSingleStatement(sql, configuration.getDbType());
        return translate(stmt);
    }

    private SQLStatementTranslator translate(SQLStatement stmt) {
        StatementResolverVisitor visitor = new StatementResolverVisitor(configuration);
        stmt.accept(visitor);
        return visitor.getTranslator();
    }

    long estimatedCacheSize() {
        return cache.estimatedSize();
    }

    void cleanUpCache() {
        cache.cleanUp();
    }

    long estimatedRewriteCacheSize() {
        return rewriteCache.estimatedSize();
    }

    void cleanUpRewriteCache() {
        rewriteCache.cleanUp();
    }

}