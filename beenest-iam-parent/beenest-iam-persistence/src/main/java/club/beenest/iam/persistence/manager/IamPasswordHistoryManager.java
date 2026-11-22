package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.PasswordHistory;
import club.beenest.iam.persistence.mapper.PasswordHistoryMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 口令历史存储实现(iam_user_password_history,仅追加)。
 * <p>与口令策略 history_count 配合:新口令不得与最近 N 个历史口令相同。</p>
 */
@Component
public class IamPasswordHistoryManager {

    private final PasswordHistoryMapper passwordHistoryMapper;

    public IamPasswordHistoryManager(PasswordHistoryMapper passwordHistoryMapper) {
        this.passwordHistoryMapper = passwordHistoryMapper;
    }

    /**
     * 查询用户最近 N 条口令历史。
     *
     * @param userId 用户主键
     * @param limit 返回条数上限,小于等于 0 时返回空列表
     * @return 口令历史,按主键倒序
     */
    public List<PasswordHistory> listRecent(Long userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return passwordHistoryMapper.selectRecentByUserId(userId, limit);
    }

    /**
     * 追加一条口令历史,并清理超出保留窗口的旧行。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @param passwordHash 口令密文
     * @param keepCount 保留条数(即策略 history_count);小于等于 0 时不清理
     */
    @Transactional
    public void record(Long realmId, Long userId, String passwordHash, int keepCount) {
        PasswordHistory history = new PasswordHistory();
        history.setRealmId(realmId);
        history.setUserId(userId);
        history.setPasswordHash(passwordHash);
        passwordHistoryMapper.insert(history);
        // 保留窗口 = 策略值 + 1(含当前口令)
        if (keepCount > 0) {
            List<PasswordHistory> recent = passwordHistoryMapper.selectRecentByUserId(userId, keepCount + 1);
            if (recent.size() > keepCount) {
                passwordHistoryMapper.deleteOlderThan(userId, recent.get(recent.size() - 1).getId());
            }
        }
    }
}
