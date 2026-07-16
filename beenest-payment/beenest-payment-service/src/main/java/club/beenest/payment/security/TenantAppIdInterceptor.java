package club.beenest.payment.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.*;

/**
 * 多租户 appId 自动注入拦截器
 *
 * <p>基于 MyBatis 拦截器，在 SQL 执行前自动追加 {@code AND app_id = ?} 条件，
 * 或在 INSERT/UPDATE 时自动为参数对象的 {@code appId} 字段赋值，
 * 无需在每个 Mapper XML 中手动编写，避免遗漏。</p>
 *
 * <h3>核心逻辑</h3>
 * <ul>
 *   <li><b>SELECT</b>：自动在 WHERE 子句末尾追加 {@code AND app_id = ?}，参数从 {@link AppContext#getAppId()} 获取</li>
 *   <li><b>INSERT</b>：自动为参数对象的 {@code appId} 字段赋值（从 AppContext 获取）</li>
 *   <li><b>UPDATE</b>：同时做赋值 + 追加 WHERE 条件</li>
 * </ul>
 *
 * <h3>跳过条件</h3>
 * <ul>
 *   <li>{@link AppContext#getAppId()} 为 null（定时任务、MQ 消费者等非请求上下文）</li>
 *   <li>Mapper 方法标注了 {@link TenantIgnore @TenantIgnore}</li>
 *   <li>SQL 涉及的表不在白名单中（不含 app_id 列的系统表）</li>
 * </ul>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@Component
public class TenantAppIdInterceptor implements Interceptor {

    /**
     * 需要多租户隔离的表名（含 app_id 列的业务表）
     */
    private static final Set<String> TENANT_TABLES = Set.of(
            "ds_payment_order",
            "ds_payment_event",
            "ds_refund",
            "ds_wallet",
            "ds_wallet_transaction",
            "ds_withdraw_request",
            "ds_service_order",
            "ds_credit_authorization"
    );

    /** 拦截器追加的参数名前缀，避免与业务参数冲突 */
    private static final String APP_ID_PARAM_KEY = "_tenantAppId";

    /** Mapper 方法缓存：className#methodName → 是否标注 @TenantIgnore */
    private final Map<String, Boolean> tenantIgnoreCache = new HashMap<>();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 获取 appId，为空则跳过（定时任务、MQ 消费者等非请求上下文）
        String appId = AppContext.getAppId();
        if (appId == null || appId.isBlank()) {
            return invocation.proceed();
        }

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType commandType = ms.getSqlCommandType();

        // 2. 检查 @TenantIgnore 注解
        if (isTenantIgnored(ms)) {
            return invocation.proceed();
        }

        // 3. 根据操作类型处理
        if (commandType == SqlCommandType.SELECT) {
            return handleSelect(invocation, ms, appId);
        } else if (commandType == SqlCommandType.INSERT) {
            return handleInsert(invocation, appId);
        } else if (commandType == SqlCommandType.UPDATE) {
            return handleUpdate(invocation, ms, appId);
        }

        return invocation.proceed();
    }

    /**
     * SELECT：自动追加 AND app_id = ? 条件
     */
    private Object handleSelect(Invocation invocation, MappedStatement ms, String appId) throws Throwable {
        BoundSql boundSql = ms.getBoundSql(invocation.getArgs()[1]);
        String originalSql = boundSql.getSql();

        // 检查 SQL 是否涉及需要租户隔离的表
        if (!involvesTenantTable(originalSql)) {
            return invocation.proceed();
        }

        // SQL 已包含 app_id 条件，避免重复追加
        if (containsAppIdCondition(originalSql)) {
            return invocation.proceed();
        }

        // 追加 AND app_id = ?
        String newSql = originalSql + " AND app_id = ?";

        // 构建新的 BoundSql，追加参数
        List<ParameterMapping> newMappings = new ArrayList<>(boundSql.getParameterMappings());
        newMappings.add(new ParameterMapping.Builder(ms.getConfiguration(), APP_ID_PARAM_KEY, String.class)
                .jdbcType(JdbcType.VARCHAR).build());

        BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), newSql, newMappings, boundSql.getParameterObject());

        // 复制原有附加参数
        copyAdditionalParameters(boundSql, newBoundSql);

        // 设置追加的 appId 参数值
        newBoundSql.setAdditionalParameter(APP_ID_PARAM_KEY, appId);

        // 替换 MappedStatement 中的 BoundSql
        MappedStatement newMs = copyMappedStatement(ms, newBoundSql);
        invocation.getArgs()[0] = newMs;

        return invocation.proceed();
    }

    /**
     * INSERT：自动为参数对象的 appId 字段赋值
     */
    private Object handleInsert(Invocation invocation, String appId) throws Throwable {
        Object parameter = invocation.getArgs()[1];
        if (parameter != null) {
            fillAppIdField(parameter, appId);
        }
        return invocation.proceed();
    }

    /**
     * UPDATE：自动赋值 + 追加 WHERE 条件
     */
    private Object handleUpdate(Invocation invocation, MappedStatement ms, String appId) throws Throwable {
        // 赋值参数对象的 appId 字段
        Object parameter = invocation.getArgs()[1];
        if (parameter != null) {
            fillAppIdField(parameter, appId);
        }

        // 追加 WHERE AND app_id = ?
        BoundSql boundSql = ms.getBoundSql(parameter);
        String originalSql = boundSql.getSql();

        if (!involvesTenantTable(originalSql)) {
            return invocation.proceed();
        }

        if (containsAppIdCondition(originalSql)) {
            return invocation.proceed();
        }

        String newSql = originalSql + " AND app_id = ?";

        List<ParameterMapping> newMappings = new ArrayList<>(boundSql.getParameterMappings());
        newMappings.add(new ParameterMapping.Builder(ms.getConfiguration(), APP_ID_PARAM_KEY, String.class)
                .jdbcType(JdbcType.VARCHAR).build());

        BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), newSql, newMappings, boundSql.getParameterObject());
        copyAdditionalParameters(boundSql, newBoundSql);
        newBoundSql.setAdditionalParameter(APP_ID_PARAM_KEY, appId);

        MappedStatement newMs = copyMappedStatement(ms, newBoundSql);
        invocation.getArgs()[0] = newMs;

        return invocation.proceed();
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查 Mapper 方法是否标注了 @TenantIgnore
     */
    private boolean isTenantIgnored(MappedStatement ms) {
        String statementId = ms.getId();
        return tenantIgnoreCache.computeIfAbsent(statementId, id -> {
            int lastDot = id.lastIndexOf('.');
            if (lastDot < 0) return false;
            String className = id.substring(0, lastDot);
            String methodName = id.substring(lastDot + 1);
            try {
                Class<?> mapperClass = Class.forName(className);
                Method method = ReflectionUtils.findMethod(mapperClass, methodName);
                return method != null && method.isAnnotationPresent(TenantIgnore.class);
            } catch (ClassNotFoundException e) {
                // Mapper 类找不到（可能是动态生成的），默认不跳过
                return false;
            }
        });
    }

    /**
     * 检查 SQL 是否涉及需要租户隔离的表
     */
    private boolean involvesTenantTable(String sql) {
        if (sql == null) return false;
        String lowerSql = sql.toLowerCase();
        for (String table : TENANT_TABLES) {
            if (lowerSql.contains(table)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 SQL 是否已在 WHERE/ON 子句中包含 app_id 条件（避免重复追加）。
     *
     * <p>仅匹配 WHERE/ON 子句中的 {@code app_id = ?}、{@code app_id = #{appId}}、
     * {@code t.app_id = ?} 等条件形式，不匹配 INSERT 列名、resultMap 引用等。</p>
     */
    private boolean containsAppIdCondition(String sql) {
        if (sql == null) return false;
        // 匹配 WHERE/ON 子句中的 app_id 条件：
        // - app_id = ? / app_id = #{...} / app_id IN (...)
        // - 支持表别名：t.app_id = ?
        // 排除 INSERT 列名（INSERT INTO ... app_id, ...）和 resultMap 引用
        String pattern = "(?i)(?:WHERE|ON|AND|OR)\\s+[\\w.]*app_id\\s*(?:=|IN\\s*\\()";
        return java.util.regex.Pattern.compile(pattern).matcher(sql).find();
    }

    /**
     * 通过反射为参数对象的 appId 字段赋值（仅当字段为 null 时）
     */
    private void fillAppIdField(Object parameter, String appId) {
        try {
            // 处理 MapperMethod.ParamMap 类型（@Param 多参数场景）
            if (parameter instanceof Map) {
                // 多参数场景：尝试设置 map 中的 appId 键
                Map<?, ?> paramMap = (Map<?, ?>) parameter;
                if (!paramMap.containsKey("appId")) {
                    return;
                }
                Object existing = ((Map) parameter).get("appId");
                if (existing == null || (existing instanceof String && ((String) existing).isBlank())) {
                    ((Map) parameter).put("appId", appId);
                }
                return;
            }

            // 单参数对象：通过反射设置 appId 字段
            java.lang.reflect.Field field = ReflectionUtils.findField(parameter.getClass(), "appId");
            if (field == null) return;

            field.setAccessible(true);
            Object existing = field.get(parameter);
            if (existing == null || (existing instanceof String && ((String) existing).isBlank())) {
                field.set(parameter, appId);
            }
        } catch (Exception e) {
            // 反射失败不影响正常流程
            log.debug("自动填充 appId 字段失败: {}", e.getMessage());
        }
    }

    /**
     * 复制 BoundSql 中的附加参数到新的 BoundSql
     */
    private void copyAdditionalParameters(BoundSql source, BoundSql target) {
        for (ParameterMapping mapping : source.getParameterMappings()) {
            String prop = mapping.getProperty();
            if (prop.startsWith("_")) {
                // MyBatis 内部参数（如 _frch_item_0）通过 additionalParameters 传递
                Object value = source.getAdditionalParameter(prop);
                if (value != null) {
                    target.setAdditionalParameter(prop, value);
                }
            }
        }
    }

    /**
     * 复制 MappedStatement，替换 BoundSql
     */
    private MappedStatement copyMappedStatement(MappedStatement ms, BoundSql newBoundSql) {
        MappedStatement.Builder builder = new MappedStatement.Builder(
                ms.getConfiguration(), ms.getId(), boundSql -> newBoundSql, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.parameterMap(ms.getParameterMap());
        builder.resultMaps(ms.getResultMaps());
        builder.fetchSize(ms.getFetchSize());
        builder.timeout(ms.getTimeout());
        builder.statementType(ms.getStatementType());
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());
        builder.keyGenerator(ms.getKeyGenerator());
        builder.keyProperty(arrayToString(ms.getKeyProperties()));
        builder.keyColumn(arrayToString(ms.getKeyColumns()));
        builder.databaseId(ms.getDatabaseId());
        builder.lang(ms.getLang());
        builder.resultOrdered(ms.isResultOrdered());
        builder.resultSets(arrayToString(ms.getResultSets()));
        return builder.build();
    }

    private String arrayToString(String[] arr) {
        return arr == null || arr.length == 0 ? null : String.join(",", arr);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }
}
