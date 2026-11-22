package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.PasswordHistory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 口令历史 Mapper(iam_user_password_history,仅追加与按窗口清理)。
 * <p>历史口令以哈希存储,表中无逻辑删除列,清理为物理删除。</p>
 */
public interface PasswordHistoryMapper {

    /**
     * 查询用户最近若干条口令历史,按主键倒序。
     *
     * @param userId 用户主键
     * @param limit  返回条数上限
     * @return 口令历史列表;无记录时返回空列表
     */
    List<PasswordHistory> selectRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 插入口令历史。
     *
     * @param passwordHistory 口令历史实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(PasswordHistory passwordHistory);

    /**
     * 删除用户超出保留窗口的口令历史,物理删除。
     *
     * @param userId 用户主键
     * @param keepId 保留窗口的最小主键,主键小于该值的记录被删除
     * @return 被删除的记录数
     */
    int deleteOlderThan(@Param("userId") Long userId, @Param("keepId") Long keepId);
}
