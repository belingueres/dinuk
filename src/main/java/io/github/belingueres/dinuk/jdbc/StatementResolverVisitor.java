package io.github.belingueres.dinuk.jdbc;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLBooleanExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

/**
 *
 */
public class StatementResolverVisitor extends SQLASTVisitorAdapter {

    private final Configuration configuration;
    private SQLStatementTranslator translator;

    /**
     * @param configuration the partition configuration used to resolve
     *         statements visited by this instance
     */
    public StatementResolverVisitor(Configuration configuration) {
        this.configuration = configuration;
    }

    public SQLStatementTranslator getTranslator() {
        return translator;
    }

    @Override
    public boolean visit(SQLInsertStatement insertStmt) {
        String original = insertStmt.getTableName().getSimpleName();
        Configuration.TableConfig config = configuration.getTableConfigForTable(original);
        if (config == null || insertStmt.getValuesList().size() > 1) {
            return true;
        }

        int columnIndex = findColumnIndex(insertStmt.getColumns(), config.getPartitionKey());
        if (columnIndex < 0) {
            return true;
        }

        List<SQLExpr> firstRowValues = insertStmt.getValuesList().get(0).getValues();
        if (columnIndex >= firstRowValues.size()) {
            return true;
        }

        SQLExpr partitionKeyValue = firstRowValues.get(columnIndex);
        if (partitionKeyValue == null) {
            return true;
        }

        boolean valueMapped = config.isValueMapped();
        if (!PartitionKeyUtils.isParameter(partitionKeyValue) && resolveTargetTable(original, config, partitionKeyValue) == null) {
            return true;
        }

        translator = new SQLStatementTranslator();
        String target;
        if (PartitionKeyUtils.isParameter(partitionKeyValue)) {
            // Can't determine partition key value now.
            // Defer rewriting to runtime when the value is available in PreparedStatement parameters.
            // Count parameters up to the partition key value in VALUES to get actual parameter index
            int paramCountBeforeKey = countParametersBeforeIndex(firstRowValues, columnIndex);
            int actualParamIndex = paramCountBeforeKey + 1; // 1-based
            if (valueMapped) {
                // Keep the partition key column and substitute the placeholder so the physical
                // table still stores the partition key value identifying the row within its range.
                firstRowValues.set(columnIndex, new SQLIdentifierExpr(SQLStatementTranslator.DEFERRED_KEY));
                translator.setRemovedColumnIndex(-1); // No column removed
            } else {
                // Key mode: remove partition key from columns and values
                removePartitionKeyColumn(insertStmt, columnIndex);
                translator.setRemovedColumnIndex(columnIndex + 1); // Track which column index was removed
                                                                   // (1-based)
            }
            target = deferredOrFixedTarget(original, partitionKeyValue, actualParamIndex, valueMapped, config);
        } else {
            // Fixed partition key value - keep the partition key column and value, just route to correct
            // table
            translator.setDeferredPartitionKeyIndex(-1);
            translator.setRemovedColumnIndex(-1); // Not removed

            if (!valueMapped) {
                // Key mode: remove partition key from columns and values
                removePartitionKeyColumn(insertStmt, columnIndex);
            }

            target = resolveTargetTable(original, config, partitionKeyValue);
        }

        insertStmt.getTableSource().setExpr(new SQLIdentifierExpr(target));

        String rewrittenSqlString = SQLUtils.toSQLString(insertStmt, configuration.getDbType());
        translator.setRewrittenSql(rewrittenSqlString);

        return true;
    }

    @Override
    public boolean visit(SQLUpdateStatement updateStmt) {
        return handleUpdateDelete(updateStmt.getTableName().getSimpleName(), updateStmt.getTableSource(),
                updateStmt.getWhere(), updateStmt);
    }

    @Override
    public boolean visit(SQLDeleteStatement deleteStmt) {
        return handleUpdateDelete(deleteStmt.getTableName().getSimpleName(), deleteStmt.getTableSource(),
                deleteStmt.getWhere(), deleteStmt);
    }

    @Override
    public boolean visit(SQLSelectStatement selectStmt) {
        if (selectStmt.getSelect() == null) {
            return true;
        }

        SQLSelectQuery query = selectStmt.getSelect().getQuery();

        // Handle UNION / INTERSECT / EXCEPT queries
        if (query instanceof SQLUnionQuery) {
            return handleUnionQuery(selectStmt, (SQLUnionQuery) query);
        }

        if (!(query instanceof SQLSelectQueryBlock)) {
            return true;
        }

        SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) query;

        if (processSingleQueryBlock(selectStmt, queryBlock)) {
            translator.setRewrittenSql(selectStmt.toString());
            return false;
        }
        return true;
    }

    private boolean handleUnionQuery(SQLSelectStatement selectStmt, SQLUnionQuery unionQuery) {
        processUnionSide(selectStmt, unionQuery.getLeft());
        processUnionSide(selectStmt, unionQuery.getRight());
        if (translator != null) {
            translator.setRewrittenSql(selectStmt.toString());
            return false;
        }
        return true;
    }

    private void processUnionSide(SQLSelectStatement selectStmt, SQLSelectQuery query) {
        if (query instanceof SQLUnionQuery) {
            handleUnionQuery(selectStmt, (SQLUnionQuery) query);
        } else if (query instanceof SQLSelectQueryBlock) {
            processSingleQueryBlock(selectStmt, (SQLSelectQueryBlock) query);
        }
    }

    private boolean processSingleQueryBlock(SQLSelectStatement selectStmt, SQLSelectQueryBlock queryBlock) {
        SQLTableSource tableSource = queryBlock.getFrom();

        // Delegate to JOIN handlers if applicable
        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource joinSource = (SQLJoinTableSource) tableSource;
            if (joinSource.getCondition() != null) {
                return handleMultiTable(selectStmt, queryBlock, joinSource);
            }
            return handleCommaJoinSelect(selectStmt, queryBlock, joinSource);
        }

        if (!(tableSource instanceof SQLExprTableSource)) {
            return false;
        }

        SQLExprTableSource exprSource = (SQLExprTableSource) tableSource;
        String tableName = exprSource.getTableName();
        String tableAlias = exprSource.getAlias();
        Configuration.TableConfig config = tableName != null ? configuration.getTableConfigForTable(tableName) : null;
        if (config == null) {
            return false;
        }

        return rewriteSingleTable(queryBlock, exprSource, tableName, tableAlias, config);
    }

    private boolean rewriteSingleTable(SQLSelectQueryBlock queryBlock, SQLExprTableSource exprSource,
            String tableName, String tableAlias, Configuration.TableConfig config) {
        SQLExpr where = queryBlock.getWhere();
        if (where == null) {
            return false;
        }

        List<SQLExpr> conditions = SQLBinaryOpExpr.split(where, SQLBinaryOperator.BooleanAnd);
        SQLExpr partitionKeyValue = resolveSingleTableValue(tableName, where, tableAlias, config, conditions);
        if (partitionKeyValue == null) {
            return false;
        }

        boolean isDeferred = PartitionKeyUtils.isParameter(partitionKeyValue);
        ensureTranslator();

        int paramIndex = isDeferred ? findParameterIndexBeforeValue(conditions, partitionKeyValue) : -1;
        String target = resolveSingleTableTarget(tableName, config, partitionKeyValue,
                conditions, tableAlias, paramIndex);

        exprSource.setExpr(new SQLIdentifierExpr(target));

        // Value-mapped tables (range/list/hash) store the partition key column, so their
        // SELECT list keeps the column reference unchanged. Key-mode tables lack the column,
        // so the reference is replaced with the literal value (or the deferred placeholder).
        replaceSingleTablePartitionKey(queryBlock.getSelectList(), tableAlias, config, partitionKeyValue, isDeferred);

        queryBlock.setWhere(rebuildAndConditions(conditions));

        return true;
    }

    private SQLExpr resolveSingleTableValue(String tableName, SQLExpr where, String alias,
            Configuration.TableConfig config, List<SQLExpr> conditions) {
        if (hasOrOnPartitionKey(where, alias, config)) {
            return null;
        }
        SQLExpr value = findPartitionValue(conditions, alias, config.getPartitionKey());
        if (value != null && !PartitionKeyUtils.isParameter(value)
                && resolveTargetTable(tableName, config, value) == null) {
            return null;
        }
        return value;
    }

    private boolean bail() {
        translator = null;
        return false;
    }

    private void ensureTranslator() {
        if (translator == null) {
            translator = new SQLStatementTranslator();
        }
    }

    private boolean handleMultiTable(SQLSelectStatement selectStmt,
            SQLSelectQueryBlock queryBlock,
            SQLJoinTableSource joinSource) {
        // Collect all leaf table sources from the join tree
        List<SQLExprTableSource> tableSources = new ArrayList<>();
        collectTableSources(joinSource, tableSources);

        // Find which are configured
        List<SQLExprTableSource> configured = new ArrayList<>();
        List<Configuration.TableConfig> configs = new ArrayList<>();
        for (SQLExprTableSource source : tableSources) {
            String name = source.getTableName();
            Configuration.TableConfig config = name != null ? configuration.getTableConfigForTable(name) : null;
            if (config != null) {
                configured.add(source);
                configs.add(config);
            }
        }
        if (configured.isEmpty()) return false;

        ensureTranslator();

        boolean hasJoinCondition = joinSource.getCondition() != null;
        boolean bothConfiguredTwoLeaf = hasJoinCondition && tableSources.size() == 2 && configured.size() == 2;

        SQLExpr where = queryBlock.getWhere();
        List<SQLExpr> whereParts = where != null
                ? SQLBinaryOpExpr.split(where, SQLBinaryOperator.BooleanAnd)
                : new ArrayList<>();
        List<SQLExpr> keptWhereParts = new ArrayList<>(whereParts);
        List<SQLExpr> retainedWhere = new ArrayList<>();

        List<SQLExpr> onParts = hasJoinCondition
                ? SQLBinaryOpExpr.split(joinSource.getCondition(), SQLBinaryOperator.BooleanAnd)
                : new ArrayList<>();
        List<SQLExpr> allOnParts = new ArrayList<>();
        if (hasJoinCondition) {
            collectJoinConditions(joinSource, allOnParts);
        }
        List<SQLExpr> keptOnParts = new ArrayList<>(onParts);
        List<SQLExpr> retainedOn = new ArrayList<>();

        SQLExpr[] partitionValues = new SQLExpr[configured.size()];
        boolean[] foundInOn = new boolean[configured.size()];

        for (int i = 0; i < configured.size(); i++) {
            SQLExprTableSource source = configured.get(i);
            Configuration.TableConfig config = configs.get(i);
            String alias = source.getAlias();

            // Comma joins route partition keys through WHERE; ON joins may also use the ON clause
            if (!hasJoinCondition && hasOrOnPartitionKey(where, alias, config)) {
                return bail();
            }

            SQLExpr value;
            if (bothConfiguredTwoLeaf) {
                // 2-table ON join: look up the partition keys in the ON clause first, then in WHERE
                value = consumePartitionCondition(keptOnParts, alias, config, retainedOn);
                if (value != null) {
                    foundInOn[i] = true;
                } else {
                    value = consumePartitionCondition(keptWhereParts, alias, config, retainedWhere);
                }
            } else {
                // Comma and 3+ table ON joins: WHERE clause first, then the ON conditions
                value = consumePartitionCondition(keptWhereParts, alias, config, retainedWhere);
                if (value == null && hasJoinCondition) {
                    value = findAndRemovePartitionKeyInJoin(joinSource, alias, config, retainedOn);
                    if (value != null) {
                        foundInOn[i] = true;
                    }
                }
            }
            if (value == null) {
                return bail();
            }

            partitionValues[i] = value;
            if (!renameConfiguredTable(source, source.getTableName(), config, value, i + 1)) {
                return bail();
            }
        }

        // Deferred partition keys are registered so their placeholders can be substituted at execution time.
        List<SQLExpr> parameterOrder = new ArrayList<>(allOnParts);
        parameterOrder.addAll(whereParts);
        for (int i = 0; i < configured.size(); i++) {
            SQLExpr value = partitionValues[i];
            if (PartitionKeyUtils.isParameter(value)) {
                translator.addDeferredKey(i + 1, findParameterIndexBeforeValue(
                        foundInOn[i] ? allOnParts : parameterOrder, value));
            }
        }
        // Substitute the value placeholders only after every parameter index has been
        // computed, so earlier substitutions cannot change what later lookups count.
        for (int i = 0; i < configured.size(); i++) {
            SQLExpr value = partitionValues[i];
            if (PartitionKeyUtils.isParameter(value) && configs.get(i).isValueMapped()) {
                String placeholder = SQLStatementTranslator.deferredKey(i + 1);
                substituteRetainedPartitionKey(retainedWhere, configured.get(i).getAlias(),
                        configs.get(i).getPartitionKey(), placeholder);
                substituteRetainedPartitionKey(retainedOn, configured.get(i).getAlias(),
                        configs.get(i).getPartitionKey(), placeholder);
            }
        }
        if (bothConfiguredTwoLeaf) {
            translator.setDeferredPartitionKeyIndex(translator.getFirstDeferredParamIndex());
        }

        // Replace partition key references in the SELECT list — per-table deferred
        List<SQLSelectItem> selectItems = queryBlock.getSelectList();
        if (selectItems != null) {
            for (SQLSelectItem item : selectItems) {
                for (int i = 0; i < configured.size(); i++) {
                    SQLExprTableSource source = configured.get(i);
                    Configuration.TableConfig config = configs.get(i);
                    SQLExpr value = partitionValues[i];
                    if (replaceJoinPartitionKey(item, source.getAlias(), config, value,
                            SQLStatementTranslator.deferredKey(i + 1),
                            PartitionKeyUtils.isParameter(value))) {
                        break;
                    }
                }
            }
        }

        // The 2-table ON join rebuilds ON/WHERE from the consumed conditions; the generic
        // path additionally rewrites the remaining partition key references in place.
        if (!bothConfiguredTwoLeaf) {
            for (int i = 0; i < configured.size(); i++) {
                SQLExprTableSource source = configured.get(i);
                Configuration.TableConfig config = configs.get(i);
                SQLExpr value = partitionValues[i];
                SQLExpr replacement = PartitionKeyUtils.isParameter(value)
                        ? new SQLIdentifierExpr(SQLStatementTranslator.deferredKey(i + 1))
                        : value;

                // Value-mapped tables keep the partition column; only key-mode tables need refs replaced.
                if (!config.isValueMapped()) {
                    // Replace in kept WHERE conditions - skip retained range partition conditions
                    List<SQLExpr> nonRetained = new ArrayList<>();
                    for (SQLExpr candidate : keptWhereParts) {
                        if (!retainedWhere.contains(candidate)) {
                            nonRetained.add(candidate);
                        }
                    }
                    replacePartitionKeyRefs(nonRetained, source.getAlias(), config.getPartitionKey(), replacement);

                    // Replace in all join conditions
                    if (hasJoinCondition) {
                        replacePartitionKeyRefsInJoin(joinSource, source.getAlias(), config.getPartitionKey(), replacement);
                    }
                }
            }
        }

        if (bothConfiguredTwoLeaf && hasJoinCondition) {
            joinSource.setCondition(rebuildAndConditions(keptOnParts));
        }

        queryBlock.setWhere(rebuildAndConditions(keptWhereParts));

        translator.setRewrittenSql(selectStmt.toString());
        return true;
    }

    private void collectTableSources(SQLTableSource source, List<SQLExprTableSource> result) {
        if (source instanceof SQLExprTableSource) {
            result.add((SQLExprTableSource) source);
        } else if (source instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) source;
            collectTableSources(join.getLeft(), result);
            collectTableSources(join.getRight(), result);
        }
    }

    private void collectJoinConditions(SQLTableSource source, List<SQLExpr> result) {
        if (source instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) source;
            collectJoinConditions(join.getLeft(), result);
            collectJoinConditions(join.getRight(), result);
            if (join.getCondition() != null) {
                result.addAll(SQLBinaryOpExpr.split(join.getCondition(), SQLBinaryOperator.BooleanAnd));
            }
        }
    }

    private void replacePartitionKeyRefs(List<SQLExpr> conditions, String alias, String partitionKey, SQLExpr valueExpr) {
        for (int i = 0; i < conditions.size(); i++) {
            conditions.set(i, replacePartitionKeyRef(conditions.get(i), alias, partitionKey, valueExpr));
        }
    }

    private SQLExpr replacePartitionKeyRef(SQLExpr expr, String alias, String partitionKey, SQLExpr valueExpr) {
        if (PartitionKeyUtils.isPartitionKeyReference(expr, alias, partitionKey)) {
            return valueExpr;
        }
        if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            SQLExpr left = binary.getLeft();
            SQLExpr right = binary.getRight();
            SQLExpr newLeft = replacePartitionKeyRef(left, alias, partitionKey, valueExpr);
            SQLExpr newRight = replacePartitionKeyRef(right, alias, partitionKey, valueExpr);
            if (newLeft != left || newRight != right) {
                binary.setLeft(newLeft);
                binary.setRight(newRight);
            }
        }
        return expr;
    }

    private void replacePartitionKeyRefsInJoin(SQLTableSource source, String alias, String partitionKey, SQLExpr valueExpr) {
        if (source instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) source;
            SQLExpr cond = join.getCondition();
            if (cond != null) {
                SQLExpr replaced = replacePartitionKeyRef(cond, alias, partitionKey, valueExpr);
                if (replaced != cond) {
                    join.setCondition(replaced);
                }
            }
            replacePartitionKeyRefsInJoin(join.getLeft(), alias, partitionKey, valueExpr);
            replacePartitionKeyRefsInJoin(join.getRight(), alias, partitionKey, valueExpr);
        }
    }

    private SQLExpr findAndRemovePartitionKeyInJoin(SQLJoinTableSource join, String alias,
            Configuration.TableConfig config, List<SQLExpr> retained) {
        SQLExpr cond = join.getCondition();
        if (cond != null) {
            List<SQLExpr> parts = SQLBinaryOpExpr.split(cond, SQLBinaryOperator.BooleanAnd);
            List<SQLExpr> kept = new ArrayList<>();
            SQLExpr found = null;
            for (SQLExpr part : parts) {
                if (found == null && PartitionKeyUtils.isPartitionKeyEquality(part, alias, config.getPartitionKey())) {
                    found = ((SQLBinaryOpExpr) part).getRight();
                    if (config.isValueMapped()) {
                        retained.add(part);
                        kept.add(part);
                    }
                    continue;
                }
                kept.add(part);
            }
            if (found != null) {
                join.setCondition(rebuildAndConditions(kept));
                return found;
            }
        }
        if (join.getLeft() instanceof SQLJoinTableSource) {
            SQLExpr value = findAndRemovePartitionKeyInJoin((SQLJoinTableSource) join.getLeft(), alias, config, retained);
            if (value != null) return value;
        }
        if (join.getRight() instanceof SQLJoinTableSource) {
            SQLExpr value = findAndRemovePartitionKeyInJoin((SQLJoinTableSource) join.getRight(), alias, config, retained);
            if (value != null) return value;
        }
        return null;
    }

    private boolean handleCommaJoinSelect(SQLSelectStatement selectStmt,
            SQLSelectQueryBlock queryBlock,
            SQLJoinTableSource joinSource) {
        SQLTableSource left = joinSource.getLeft();
        SQLTableSource right = joinSource.getRight();

        // For 3+ table COMMA joins, process all leaves together
        if (!(left instanceof SQLExprTableSource) || !(right instanceof SQLExprTableSource)) {
            return handleMultiTable(selectStmt, queryBlock, joinSource);
        }

        SQLExprTableSource leftSource = (SQLExprTableSource) left;
        SQLExprTableSource rightSource = (SQLExprTableSource) right;
        String leftName = leftSource.getTableName();
        String rightName = rightSource.getTableName();
        String leftAlias = leftSource.getAlias();
        String rightAlias = rightSource.getAlias();

        Configuration.TableConfig leftConfig = leftName != null ? configuration.getTableConfigForTable(leftName) : null;
        Configuration.TableConfig rightConfig = rightName != null ? configuration.getTableConfigForTable(rightName) : null;

        if (leftConfig == null && rightConfig == null) return false;

        List<SQLSelectItem> selectItems = queryBlock.getSelectList();
        if (selectItems == null || selectItems.isEmpty()) return false;

        SQLExpr where = queryBlock.getWhere();
        if (where == null) return false;

        // Check OR on partition key for each configured table with its own partition key
        if (hasOrOnPartitionKey(where, leftAlias, leftConfig)) return false;
        if (hasOrOnPartitionKey(where, rightAlias, rightConfig)) return false;

        List<SQLExpr> conditions = SQLBinaryOpExpr.split(where, SQLBinaryOperator.BooleanAnd);
        List<SQLExpr> retainedLeft = new ArrayList<>();
        List<SQLExpr> retainedRight = new ArrayList<>();

        SQLExpr leftPartitionCondition = leftConfig != null
                ? findPartitionCondition(conditions, leftAlias, leftConfig.getPartitionKey()) : null;
        if (leftPartitionCondition != null && leftConfig.isValueMapped()) {
            retainedLeft.add(leftPartitionCondition);
        }
        SQLExpr rightPartitionCondition = rightConfig != null
                ? findPartitionCondition(conditions, rightAlias, rightConfig.getPartitionKey()) : null;
        if (rightPartitionCondition != null && rightConfig.isValueMapped()) {
            retainedRight.add(rightPartitionCondition);
        }
        SQLExpr leftPartitionValue = findPartitionValue(conditions, leftAlias,
                leftConfig != null ? leftConfig.getPartitionKey() : null);
        SQLExpr rightPartitionValue = findPartitionValue(conditions, rightAlias,
                rightConfig != null ? rightConfig.getPartitionKey() : null);

        if (leftConfig != null && leftPartitionValue == null) return false;
        if (rightConfig != null && rightPartitionValue == null) return false;

        boolean bothConfigured = leftConfig != null && rightConfig != null;
        if (bothConfigured && PartitionKeyUtils.isParameter(leftPartitionValue) != PartitionKeyUtils.isParameter(rightPartitionValue)) return false;

        if ((leftConfig != null && !PartitionKeyUtils.isParameter(leftPartitionValue) && resolveTargetTable(leftName, leftConfig, leftPartitionValue) == null)
                || (rightConfig != null && !PartitionKeyUtils.isParameter(rightPartitionValue) && resolveTargetTable(rightName, rightConfig, rightPartitionValue) == null)) {
            return false;
        }

        translator = new SQLStatementTranslator();
        boolean isDeferred = bothConfigured
                ? PartitionKeyUtils.isParameter(leftPartitionValue)
                : PartitionKeyUtils.isParameter(leftPartitionValue != null ? leftPartitionValue : rightPartitionValue);

        if (bothConfigured) {
            if (isDeferred) {
                renameConfiguredTable(leftSource, leftName, leftConfig, leftPartitionValue, 1);
                renameConfiguredTable(rightSource, rightName, rightConfig, rightPartitionValue, 2);
                translator.addDeferredKey(1, findParameterIndexBeforeValue(conditions, leftPartitionValue));
                translator.addDeferredKey(2, findParameterIndexBeforeValue(conditions, rightPartitionValue));
                translator.setDeferredPartitionKeyIndex(translator.getFirstDeferredParamIndex());
            } else {
                renameConfiguredTable(leftSource, leftName, leftConfig, leftPartitionValue, 1);
                renameConfiguredTable(rightSource, rightName, rightConfig, rightPartitionValue, 2);
            }

            // Replace partition key references in SELECT list
            for (SQLSelectItem item : selectItems) {
                boolean replaced = replaceJoinPartitionKey(item, leftAlias, leftConfig, leftPartitionValue,
                        SQLStatementTranslator.deferredKey(1), isDeferred);
                if (!replaced) {
                    replaceJoinPartitionKey(item, rightAlias, rightConfig, rightPartitionValue,
                            SQLStatementTranslator.deferredKey(2), isDeferred);
                }
            }
        } else {
            // Single configured table
            boolean leftIsConfigured = leftConfig != null;
            SQLExpr partitionValue = leftIsConfigured ? leftPartitionValue : rightPartitionValue;
            String alias = leftIsConfigured ? leftAlias : rightAlias;
            Configuration.TableConfig config = leftIsConfigured ? leftConfig : rightConfig;
            String tableName = leftIsConfigured ? leftName : rightName;
            SQLExprTableSource source = leftIsConfigured ? leftSource : rightSource;

            int paramIndex = isDeferred ? findParameterIndexBeforeValue(conditions, partitionValue) : -1;
            source.setExpr(new SQLIdentifierExpr(
                    resolveSingleTableTarget(tableName, config, partitionValue, conditions, alias, paramIndex)));

            replaceSingleTablePartitionKey(selectItems, alias, config, partitionValue, isDeferred);
        }

        // Range tables keep the partition key condition so the row is identified within the
        // physical table; key-mode tables drop it (encoded in the table name). Deferred
        // parameters are substituted with the matching placeholder.
        List<SQLExpr> keptConditions = new ArrayList<>();
        for (SQLExpr condition : conditions) {
            if (condition == leftPartitionCondition || condition == rightPartitionCondition) {
                continue;
            }
            keptConditions.add(condition);
        }
        if (leftConfig != null) {
            substituteRetainedPartitionKey(retainedLeft, leftAlias, leftConfig.getPartitionKey(),
                    bothConfigured ? SQLStatementTranslator.deferredKey(1) : SQLStatementTranslator.DEFERRED_KEY);
        }
        if (rightConfig != null) {
            substituteRetainedPartitionKey(retainedRight, rightAlias, rightConfig.getPartitionKey(),
                    bothConfigured ? SQLStatementTranslator.deferredKey(2) : SQLStatementTranslator.DEFERRED_KEY);
        }
        keptConditions.addAll(retainedLeft);
        keptConditions.addAll(retainedRight);

        // Remove partition key conditions from WHERE
        queryBlock.setWhere(rebuildAndConditions(keptConditions));

        translator.setRewrittenSql(selectStmt.toString());
        return true;
    }

    private boolean replaceJoinPartitionKey(SQLSelectItem item,
            String alias, Configuration.TableConfig config, SQLExpr partitionValue,
            String deferredPlaceholder, boolean isDeferred) {
        // Value-mapped tables (range/list/hash) store the partition key column, so their
        // SELECT list keeps the column reference unchanged; only key-mode tables replace it.
        if (config == null || config.isValueMapped()) {
            return false;
        }
        SQLExpr expr = item.getExpr();
        if (!PartitionKeyUtils.isPartitionKeyReference(expr, alias, config.getPartitionKey())) {
            return false;
        }
        SQLExpr replacement = isDeferred
                ? new SQLIdentifierExpr(deferredPlaceholder)
                : partitionValue;
        item.setExpr(replacement);
        String explicitAlias = item.getAlias();
        String newAlias;
        if (explicitAlias != null && !explicitAlias.isEmpty()) {
            newAlias = explicitAlias;
        } else if (expr instanceof SQLPropertyExpr) {
            newAlias = ((SQLPropertyExpr) expr).getSimpleName();
        } else {
            newAlias = config.getPartitionKey();
        }
        item.setAlias(newAlias);
        return true;
    }

    private SQLExpr findPartitionCondition(List<SQLExpr> conditions, String alias, String partitionKey) {
        for (SQLExpr part : conditions) {
            if (PartitionKeyUtils.isPartitionKeyEquality(part, alias, partitionKey)) {
                return part;
            }
        }
        return null;
    }

    private boolean hasOrOnPartitionKey(SQLExpr where, String alias, Configuration.TableConfig config) {
        return config != null && PartitionKeyUtils.containsOrOnPartitionKey(where, alias, config.getPartitionKey());
    }

    private SQLExpr findPartitionValue(List<SQLExpr> conditions, String alias, String partitionKey) {
        SQLExpr condition = findPartitionCondition(conditions, alias, partitionKey);
        return condition != null ? ((SQLBinaryOpExpr) condition).getRight() : null;
    }

    private void replaceSingleTablePartitionKey(List<SQLSelectItem> selectItems, String alias,
            Configuration.TableConfig config, SQLExpr partitionValue, boolean isDeferred) {
        if (config == null || config.isValueMapped() || selectItems == null) {
            return;
        }
        for (SQLSelectItem item : selectItems) {
            replaceJoinPartitionKey(item, alias, config, partitionValue,
                    SQLStatementTranslator.DEFERRED_KEY, isDeferred);
        }
    }

    private void substituteRetainedPartitionKey(List<SQLExpr> retained, String alias,
            String partitionKey, String placeholder) {
        for (SQLExpr part : retained) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) part;
            if (PartitionKeyUtils.isPartitionKeyReference(binary.getLeft(), alias, partitionKey)
                    && PartitionKeyUtils.isParameter(binary.getRight())) {
                binary.setRight(new SQLIdentifierExpr(placeholder));
            }
        }
    }

    private SQLExpr consumePartitionCondition(List<SQLExpr> keptParts, String alias,
            Configuration.TableConfig config, List<SQLExpr> retained) {
        SQLExpr part = findPartitionCondition(keptParts, alias, config.getPartitionKey());
        if (part == null) {
            return null;
        }
        SQLExpr value = ((SQLBinaryOpExpr) part).getRight();
        if (config.isValueMapped()) {
            // Range tables keep the partition key condition so the row is identified
            // within the physical table; the deferred placeholder is substituted later.
            retained.add(part);
        } else {
            keptParts.remove(part);
        }
        return value;
    }

    private boolean renameConfiguredTable(SQLExprTableSource source, String tableName,
            Configuration.TableConfig config, SQLExpr value, int deferredNumber) {
        if (PartitionKeyUtils.isParameter(value)) {
            boolean valueMapped = config.isValueMapped();
            source.setExpr(new SQLIdentifierExpr(valueMapped
                    ? SQLStatementTranslator.deferredTable(deferredNumber)
                    : tableName + "_" + SQLStatementTranslator.deferredTable(deferredNumber)));
            if (valueMapped) {
                translator.setDeferredKeyConfig(deferredNumber, config);
            }
            return true;
        }
        String target = resolveTargetTable(tableName, config, value);
        if (target != null) {
            source.setExpr(new SQLIdentifierExpr(target));
        }
        return target != null;
    }

    private int findParameterIndexBeforeValue(List<SQLExpr> conditions, SQLExpr targetValue) {
        int paramCount = 0;
        for (SQLExpr condition : conditions) {
            if (condition instanceof SQLBinaryOpExpr) {
                SQLBinaryOpExpr binary = (SQLBinaryOpExpr) condition;
                if (binary.getRight() == targetValue) {
                    return paramCount + 1;
                }
                if (PartitionKeyUtils.isParameter(binary.getRight())) {
                    paramCount++;
                }
            }
        }
        throw new IllegalStateException("Partition key parameter not found among WHERE/ON conditions");
    }

    private boolean handleUpdateDelete(String tableName, SQLTableSource tableSource, SQLExpr where, SQLStatement stmt) {
        Configuration.TableConfig config = configuration.getTableConfigForTable(tableName);
        if (config == null || where == null) {
            return true;
        }

        String partitionAlias = tableSource instanceof SQLExprTableSource
                ? ((SQLExprTableSource) tableSource).getAlias() : null;

        List<SQLExpr> conditions = SQLBinaryOpExpr.split(where, SQLBinaryOperator.BooleanAnd);
        SQLExpr partitionKeyValue = resolveSingleTableValue(tableName, where, partitionAlias, config, conditions);
        if (partitionKeyValue == null) {
            return true;
        }

        // A table-changing UPDATE (SET partition key = <new value>) cannot be done
        // in place when the new value maps to a different physical table: build a
        // move (INSERT into the new partition + DELETE from the old). Only UPDATEs
        // carry a SET clause that could mutate the partition key.
        if (stmt instanceof SQLUpdateStatement) {
            SQLUpdateStatement update = (SQLUpdateStatement) stmt;
            SQLUpdateSetItem pkItem = findPartitionKeySetItem(update, config);
            if (pkItem != null && buildMove(tableName, config, tableSource, update,
                    pkItem, conditions, partitionKeyValue, partitionAlias)) {
                return true;
            }
        }

        translator = new SQLStatementTranslator();
        int paramIndex = PartitionKeyUtils.isParameter(partitionKeyValue)
                ? findDeferredParameterIndex(stmt, conditions, partitionKeyValue) : -1;
        String target = resolveSingleTableTarget(tableName, config, partitionKeyValue,
                conditions, partitionAlias, paramIndex);

        setTargetTable(stmt, target);
        setWhere(stmt, rebuildAndConditions(conditions));

        translator.setRewrittenSql(SQLUtils.toSQLString(stmt, configuration.getDbType()));

        return true;
    }

    /**
     * Find the SET item that assigns the partition key column, or {@code null}
     * when the UPDATE does not touch the partition key.
     */
    private SQLUpdateSetItem findPartitionKeySetItem(SQLUpdateStatement update, Configuration.TableConfig config) {
        for (SQLUpdateSetItem item : update.getItems()) {
            SQLExpr target = item.getColumn();
            String columnName = null;
            if (target instanceof SQLIdentifierExpr) {
                columnName = ((SQLIdentifierExpr) target).getName();
            } else if (target instanceof SQLPropertyExpr) {
                columnName = ((SQLPropertyExpr) target).getName();
            }
            if (columnName != null && SQLUtils.nameEquals(columnName, config.getPartitionKey())) {
                return item;
            }
        }
        return null;
    }

    /**
     * Build the runtime move plan for a table-changing UPDATE. Registers the
     * intent on the translator; the actual INSERT..SELECT + DELETE is emitted at
     * execution time once both key values are known.
     *
     * <p>Only a key value that is a literal or a {@code ?} parameter can be
     * routed: with a non-trivial expression we cannot prove the target table, so
     * we bail (leave the statement unchanged). A same-partition assignment (SET
     * pk = pk/same literal) is not a move and is left to the in-place path.
     *
     * @return {@code true} if the statement was turned into a deferred move and
     *         the caller should return; {@code false} to continue with the
     *         normal in-place / passthrough handling.
     */
    private boolean buildMove(String tableName, Configuration.TableConfig config, SQLTableSource tableSource,
            SQLUpdateStatement update, SQLUpdateSetItem pkItem, List<SQLExpr> conditions,
            SQLExpr oldKeyValue, String partitionAlias) {
        SQLExpr newKeyValue = pkItem.getValue();
        if (newKeyValue == null) {
            return false;
        }

        // Identity / same-literal assignments are handled by the normal path.
        if (newKeyValue instanceof SQLIdentifierExpr
                && SQLUtils.nameEquals(((SQLIdentifierExpr) newKeyValue).getName(), config.getPartitionKey())) {
            return false;
        }
        if (PartitionKeyUtils.isParameter(oldKeyValue) || PartitionKeyUtils.isParameter(newKeyValue)) {
            // deferred -> decide at runtime; keep building the plan below
        } else if (valueToString(newKeyValue).equalsIgnoreCase(valueToString(oldKeyValue))) {
            return false;
        }

        // The target must be resolvable. For a deferred (?) new value it is
        // resolved at runtime; for a literal it must map to a table now, else the
        // request is nonsense and we fall back to the unchanged statement.
        boolean newKeyDeferred = PartitionKeyUtils.isParameter(newKeyValue);
        if (!newKeyDeferred
                && resolveTargetTable(tableName, config, newKeyValue) == null) {
            return false;
        }

        // Only permit non-key SET columns whose value is a literal or a ? so they
        // can be folded into the move's SELECT. Anything else bails.
        translator = new SQLStatementTranslator();
        boolean keepColumn = config.isValueMapped();
        translator.setMove(true);
        translator.setMoveKeepColumn(keepColumn);
        translator.setMoveConfig(config);

        String alias = tableSource instanceof SQLExprTableSource
                ? ((SQLExprTableSource) tableSource).getAlias() : null;

        for (SQLUpdateSetItem item : update.getItems()) {
            if (item == pkItem) {
                continue;
            }
            SQLExpr col = item.getColumn();
            String colName = col instanceof SQLIdentifierExpr ? ((SQLIdentifierExpr) col).getName()
                    : col instanceof SQLPropertyExpr ? ((SQLPropertyExpr) col).getName() : null;
            SQLExpr val = item.getValue();
            if (colName == null
                    || (!PartitionKeyUtils.isParameter(val) && !(val instanceof SQLTextLiteralExpr)
                            && !(val instanceof SQLNumberExpr) && !(val instanceof SQLBooleanExpr))) {
                translator.setMove(false);
                return false;
            }
            if (PartitionKeyUtils.isParameter(val)) {
                int setIdx = countUpdateSetParametersBefore(update, item);
                translator.addMoveSetColumn(colName, setIdx, null);
            } else {
                translator.addMoveSetColumn(colName, -1, SQLUtils.toSQLString(val, configuration.getDbType()));
            }
        }

        // Record param indices for the deferred key values.
        int newIdx = newKeyDeferred
                ? countUpdateSetParametersBefore(update, pkItem) : -1;
        translator.setMoveNewKeyParamIndex(newIdx);
        if (!newKeyDeferred) {
            translator.setMoveNewKeyLiteral(SQLUtils.toSQLString(newKeyValue, configuration.getDbType()));
            translator.setMoveNewKeyRawLiteral(valueToString(newKeyValue));
        }
        int oldIdx = PartitionKeyUtils.isParameter(oldKeyValue)
                ? findDeferredParameterIndex(update, conditions, oldKeyValue) : -1;
        translator.setMoveOldKeyParamIndex(oldIdx);
        if (oldIdx == -1) {
            translator.setMoveOldKeyRawLiteral(valueToString(oldKeyValue));
        }

        // Retain the original WHERE conditions (minus the partition-key equality
        // for key mode) so the execution can scope the move by row.
        int setParamCount = 0;
        for (SQLUpdateSetItem item : update.getItems()) {
            setParamCount += countParameters(item.getValue());
        }
        translator.setMoveWhereText(renderedMoveWhere(config, conditions, oldKeyValue, alias, keepColumn, setParamCount));
        return true;
    }

    /**
     * Renders the WHERE clause used to scope the move's SELECT and DELETE,
     * dropping the partition-key equality when key mode encodes it in the table
     * name. Each deferred ({@code ?}) value is replaced, before rendering, with
     * a placeholder identifier {@code {widx:<callerIndex>}} carrying its true
     * position in the caller's original statement (SET clause params, then
     * every WHERE condition in original order, including ones dropped here) so
     * the executor can tell which caller-bound value it is regardless of what
     * got dropped ahead of it. The old key is inlined as a literal; the rest
     * become statement parameters in order.
     *
     * @param setParamCount number of {@code ?} in the UPDATE SET clause, so the
     *                      WHERE-side caller indices can be derived
     */
    private String renderedMoveWhere(Configuration.TableConfig config, List<SQLExpr> conditions,
            SQLExpr oldKeyValue, String alias, boolean keepColumn, int setParamCount) {
        List<SQLExpr> kept = new ArrayList<>();
        for (SQLExpr condition : conditions) {
            if (!keepColumn && PartitionKeyUtils.isPartitionKeyEquality(condition, alias, config.getPartitionKey())) {
                continue;
            }
            kept.add(condition);
        }
        if (keepColumn) {
            boolean hasKey = false;
            for (SQLExpr c : kept) {
                if (PartitionKeyUtils.isPartitionKeyEquality(c, alias, config.getPartitionKey())) {
                    hasKey = true;
                    break;
                }
            }
            if (!hasKey) {
                kept.add(new SQLBinaryOpExpr(new SQLIdentifierExpr(config.getPartitionKey()),
                        SQLBinaryOperator.Equality, oldKeyValue));
            }
        }
        // Compute every caller index first, against the untouched conditions
        // list, before mutating anything: findParameterIndexBeforeValue counts
        // "?" nodes to locate each target, and replacing one in place would
        // make it invisible to that count for every lookup after it.
        Map<SQLBinaryOpExpr, Integer> callerIndices = new IdentityHashMap<>();
        for (SQLExpr condition : kept) {
            if (!(condition instanceof SQLBinaryOpExpr)) {
                continue;
            }
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) condition;
            SQLExpr right = binary.getRight();
            if (PartitionKeyUtils.isParameter(right)) {
                callerIndices.put(binary, setParamCount + findParameterIndexBeforeValue(conditions, right));
            }
        }
        for (SQLExpr condition : kept) {
            if (!(condition instanceof SQLBinaryOpExpr)) {
                continue;
            }
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) condition;
            // The move always targets one physical table with no declared
            // alias, so an alias-qualified column reference (e.g. Hibernate's
            // generated "c1_0.id") must be unqualified to a bare column name.
            if (binary.getLeft() instanceof SQLPropertyExpr) {
                binary.setLeft(new SQLIdentifierExpr(((SQLPropertyExpr) binary.getLeft()).getName()));
            }
            Integer callerIndex = callerIndices.get(binary);
            if (callerIndex != null) {
                binary.setRight(new SQLIdentifierExpr("{widx:" + callerIndex + "}"));
            }
        }
        SQLExpr rebuilt = rebuildAndConditions(kept);
        if (rebuilt == null) {
            return "";
        }
        return SQLUtils.toSQLString(rebuilt, configuration.getDbType());
    }

    private int countUpdateSetParametersBefore(SQLUpdateStatement update, SQLUpdateSetItem item) {
        int count = 0;
        int index = 0;
        for (SQLUpdateSetItem setItem : update.getItems()) {
            if (setItem == item) {
                break;
            }
            count += countParameters(setItem.getValue());
        }
        return count + 1; // 1-based JDBC index
    }

    private void setTargetTable(SQLStatement stmt, String target) {
        if (stmt instanceof SQLUpdateStatement) {
            ((SQLExprTableSource) ((SQLUpdateStatement) stmt).getTableSource()).setExpr(new SQLIdentifierExpr(target));
        } else if (stmt instanceof SQLDeleteStatement) {
            ((SQLExprTableSource) ((SQLDeleteStatement) stmt).getTableSource()).setExpr(new SQLIdentifierExpr(target));
        }
    }

    private void setWhere(SQLStatement stmt, SQLExpr where) {
        if (stmt instanceof SQLUpdateStatement) {
            ((SQLUpdateStatement) stmt).setWhere(where);
        } else if (stmt instanceof SQLDeleteStatement) {
            ((SQLDeleteStatement) stmt).setWhere(where);
        }
    }

    /**
     * Resolves the JDBC parameter index of a deferred partition key value. JDBC
     * numbers {@code ?} in statement order, so an UPDATE SET clause precedes the
     * WHERE clause; the index is therefore the count of {@code ?} in the SET
     * items plus the partition key position within the WHERE conditions.
     */
    private int findDeferredParameterIndex(SQLStatement stmt, List<SQLExpr> conditions, SQLExpr partitionKeyValue) {
        int precedingParams = 0;
        if (stmt instanceof SQLUpdateStatement) {
            for (SQLUpdateSetItem item : ((SQLUpdateStatement) stmt).getItems()) {
                precedingParams += countParameters(item.getValue());
            }
        }
        return precedingParams + findParameterIndexBeforeValue(conditions, partitionKeyValue);
    }

    private int countParameters(SQLExpr expr) {
        if (expr == null) {
            return 0;
        }
        ParameterCounter counter = new ParameterCounter();
        expr.accept(counter);
        return counter.count;
    }

    private static class ParameterCounter extends SQLASTVisitorAdapter {
        private int count = 0;

        @Override
        public boolean visit(SQLVariantRefExpr x) {
            if (PartitionKeyUtils.isParameter(x)) {
                count++;
            }
            return true;
        }
    }

    private int findColumnIndex(List<SQLExpr> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            SQLExpr col = columns.get(i);
            if (col instanceof SQLIdentifierExpr && SQLUtils.nameEquals(((SQLIdentifierExpr) col).getName(), name)) {
                return i;
            }
        }
        return -1;
    }

    private int countParametersBeforeIndex(List<SQLExpr> values, int upToIndex) {
        int count = 0;
        for (int i = 0; i < upToIndex && i < values.size(); i++) {
            if (PartitionKeyUtils.isParameter(values.get(i))) {
                count++;
            }
        }
        return count;
    }

    private void removePartitionKeyColumn(SQLInsertStatement insertStmt, int columnIndex) {
        insertStmt.getColumns().remove(columnIndex);
        for (SQLInsertStatement.ValuesClause row : insertStmt.getValuesList()) {
            List<SQLExpr> values = row.getValues();
            if (columnIndex < values.size()) {
                values.remove(columnIndex);
            }
        }
    }

    private static SQLExpr rebuildAndConditions(List<SQLExpr> parts) {
        if (parts == null || parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        SQLExpr result = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            result = new SQLBinaryOpExpr(result, SQLBinaryOperator.BooleanAnd, parts.get(i));
        }
        return result;
    }

    private String buildTargetTableName(String tableName, SQLExpr partitionKeyValue) {
        String suffix;
        if (partitionKeyValue instanceof SQLTextLiteralExpr) {
            suffix = valueToString(partitionKeyValue).toLowerCase();
        } else {
            suffix = partitionKeyValue.toString();
        }
        if (!SQLStatementTranslator.isValidTableSuffix(suffix)) {
            return null;
        }
        return tableName + "_" + suffix;
    }

    /**
     * Resolves the physical table name for a partition key value: in
     * {@code range} mode the value is looked up against the declared ranges
     * (returns {@code null} when no range matches); otherwise the key-mode
     * {@code <table>_<value>} name is built.
     */
    private String resolveTargetTable(String tableName, Configuration.TableConfig config, SQLExpr partitionValue) {
        if (config != null && config.isValueMapped()) {
            return config.getTableNameForValue(valueToString(partitionValue));
        }
        return buildTargetTableName(tableName, partitionValue);
    }

    private static String valueToString(SQLExpr expr) {
        if (expr instanceof SQLTextLiteralExpr) {
            return ((SQLTextLiteralExpr) expr).getText();
        }
        return expr.toString();
    }

    /**
     * Resolves the target physical table for a partition key value, preparing
     * the translator for deferred execution when the value is a {@code ?}
     * parameter. In value-mapped modes the table comes from the configuration;
     * in key mode the deferred table keeps the {@code {deferred}} placeholder so
     * the value (used as the table suffix) can be validated at execution time.
     */
    private String deferredOrFixedTarget(String tableName, SQLExpr partitionKeyValue, int paramIndex,
            boolean valueMapped, Configuration.TableConfig config) {
        if (PartitionKeyUtils.isParameter(partitionKeyValue)) {
            translator.setDeferredPartitionKeyIndex(paramIndex);
            if (valueMapped) {
                translator.setSingleTableConfig(config);
            }
            return valueMapped
                    ? SQLStatementTranslator.DEFERRED_TABLE
                    : tableName + "_" + SQLStatementTranslator.DEFERRED_TABLE;
        }
        return resolveTargetTable(tableName, config, partitionKeyValue);
    }

    /**
     * Resolves the physical table for a single-table partition key value and
     * adjusts the WHERE conditions: value-mapped tables keep the partition key
     * condition (a deferred parameter becomes the placeholder), key-mode tables
     * drop it (the value is encoded in the table name).
     */
    private String resolveSingleTableTarget(String tableName, Configuration.TableConfig config,
            SQLExpr partitionKeyValue, List<SQLExpr> conditions, String alias, int paramIndex) {
        boolean valueMapped = config.isValueMapped();
        SQLExpr partitionCondition = findPartitionCondition(conditions, alias, config.getPartitionKey());
        if (PartitionKeyUtils.isParameter(partitionKeyValue)) {
            translator.setRemovedColumnIndex(valueMapped ? -1 : paramIndex);
            if (valueMapped) {
                ((SQLBinaryOpExpr) partitionCondition).setRight(new SQLIdentifierExpr(SQLStatementTranslator.DEFERRED_KEY));
            } else {
                conditions.remove(partitionCondition);
            }
            return deferredOrFixedTarget(tableName, partitionKeyValue, paramIndex, valueMapped, config);
        }
        if (!valueMapped) {
            conditions.remove(partitionCondition);
        }
        return resolveTargetTable(tableName, config, partitionKeyValue);
    }

}
