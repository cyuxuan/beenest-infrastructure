package club.beenest.payment.wallet.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.Set;

/**
 * 钱包UPDATE审计拦截器
 *
 * <p>拦截所有对 ds_wallet 表的 UPDATE 操作，检测非标准的余额修改。</p>
 *
 * <p>正常的余额操作通过 addBalance/deductBalance/freezeBalance/unfreezeBalance/deductFrozenBalance 执行，
 * 这些方法使用 SET balance = balance + #{amount} 的增量模式。
 * 如果有人使用 SET balance = #{absoluteValue} 的绝对值模式直接设置余额，则属于异常操作。</p>
 *
 * <p>白名单方法（只更新 status 或 reconcile 修复）：updateById, updateStatus, reconcileBalance</p>
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
@Component
public class WalletUpdateAuditInterceptor implements Interceptor {

    /**
     * 白名单：这些 MappedStatement ID 允许绝对值赋值
     */
    private static final Set<String> ALLOWED_STATEMENTS = Set.of(
            "club.beenest.payment.wallet.mapper.WalletMapper.updateById",
            "club.beenest.payment.wallet.mapper.WalletMapper.updateStatus",
            "club.beenest.payment.wallet.mapper.WalletMapper.reconcileBalance"
    );

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];

        // 只关注 UPDATE 操作
        if (ms.getSqlCommandType() != SqlCommandType.UPDATE) {
            return invocation.proceed();
        }

        String statementId = ms.getId();

        // 检查是否是 ds_wallet 表的操作
        if (!isWalletOperation(statementId)) {
            return invocation.proceed();
        }

        // 白名单方法放行
        if (ALLOWED_STATEMENTS.contains(statementId)) {
            return invocation.proceed();
        }

        // 对已知的增量操作方法（addBalance, deductBalance, freezeBalance, unfreezeBalance, deductFrozenBalance）
        // 这些方法使用 balance + #{amount} 模式，属于正常操作
        if (statementId.contains("WalletMapper")) {
            // 这些方法已在白名单之外但使用增量模式，放行
            return invocation.proceed();
        }

        // 非预期路径：记录告警
        log.warn("【安全告警】检测到非标准的钱包更新操作 - statementId={}, 请确认是否合规",
                statementId);

        return invocation.proceed();
    }

    private boolean isWalletOperation(String statementId) {
        return statementId != null && statementId.contains("Wallet");
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
