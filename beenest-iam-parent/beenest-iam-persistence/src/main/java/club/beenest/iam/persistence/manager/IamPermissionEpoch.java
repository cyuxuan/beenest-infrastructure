package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 权限缓存代次。
 * <p>缓存键携带当前代次,任何影响权限的变更都使该域代次自增,旧代次缓存不再命中。
 * 事务内调用推迟到提交后自增,事务回滚时不自增。</p>
 */
@Component
public class IamPermissionEpoch {

    private final RedisUtils redisUtils;

    public IamPermissionEpoch(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 读取当前代次。
     * <p>键不存在时按 0 处理并补写。</p>
     *
     * @param realmId 身份域主键
     * @return 当前代次
     */
    public long current(Long realmId) {
        String key = String.format(IamRedisKeyConstants.PERMISSION_EPOCH, realmId);
        String value = redisUtils.get(key);
        if (value == null) {
            redisUtils.set(key, "0");
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 代次自增,该域全部权限缓存立即失效。
     * <p>处于事务中时注册 afterCommit 回调,提交后执行;realmId 为空时忽略。</p>
     *
     * @param realmId 身份域主键
     */
    public void bump(Long realmId) {
        if (realmId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doBump(realmId);
                }
            });
            return;
        }
        doBump(realmId);
    }

    /**
     * 写入自增计数。
     *
     * @param realmId 身份域主键
     */
    private void doBump(Long realmId) {
        String key = String.format(IamRedisKeyConstants.PERMISSION_EPOCH, realmId);
        redisUtils.increment(key);
    }
}
