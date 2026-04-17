package club.beenest.payment.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务同步工具类
 * 提供"事务提交后执行"的通用能力，防止事务回滚导致 MQ 脏消息
 */
@Slf4j
public final class TransactionSynchronizationUtils {

    private TransactionSynchronizationUtils() {
    }

    /**
     * 注册事务提交后回调；若当前无事务上下文则直接执行
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.error("事务提交后回调执行失败", e);
                    }
                }
            });
        } else {
            try {
                action.run();
            } catch (Exception e) {
                log.error("非事务上下文执行回调失败", e);
            }
        }
    }
}
