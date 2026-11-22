package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.AuditLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审计日志 Mapper(iam_audit_log)。
 * <p>日志只追加;物理删除仅用于留存期清理,且须先在同一事务内打开清理通道。</p>
 */
public interface AuditLogMapper {

    /**
     * 插入审计日志,发生时间为空时取当前时间。
     *
     * @param auditLog 审计日志实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(AuditLog auditLog);

    /**
     * 按域、动作、操作人、目标类型与时间范围查询审计日志。
     * <p>操作人模糊匹配,其余条件精确匹配;结果按发生时间与主键倒序。</p>
     *
     * @param realmId      身份域主键
     * @param action       动作编码
     * @param operatorName 操作人名称,模糊匹配
     * @param targetType   目标类型
     * @param fromTime     起始时间(含)
     * @param toTime       截止时间(含)
     * @return 日志列表;无匹配时返回空列表
     */
    List<AuditLog> selectByCondition(@Param("realmId") Long realmId,
                                     @Param("action") String action,
                                     @Param("operatorName") String operatorName,
                                     @Param("targetType") String targetType,
                                     @Param("fromTime") java.time.LocalDateTime fromTime,
                                     @Param("toTime") java.time.LocalDateTime toTime);

    /**
     * 打开本次事务的留存期清理通道。
     * <p>审计表由触发器保护,只允许在显式声明留存期清理的会话内删除;
     * 开关用 SET LOCAL 设置,随事务结束自动失效。</p>
     *
     * @return 受影响行数,通常为 0
     */
    int enablePurge();

    /**
     * 列出存在早于截止时间审计记录的身份域,按域主键升序。
     * <p>清理按域分别进行,使清理动作落到各域自己的审计轨迹。</p>
     *
     * @param cutoff 留存期截止时间
     * @param limit  单次返回的域数量上限
     * @return 域主键列表;无超期记录时返回空列表
     */
    List<Long> listRealmsWithExpired(@Param("cutoff") java.time.LocalDateTime cutoff,
                                     @Param("limit") int limit);

    /**
     * 统计某域内早于截止时间的审计记录数。
     *
     * @param realmId 身份域主键
     * @param cutoff  留存期截止时间
     * @return 记录数
     */
    long countExpired(@Param("realmId") Long realmId,
                      @Param("cutoff") java.time.LocalDateTime cutoff);

    /**
     * 删除某域内早于截止时间的审计记录。
     * <p>须先在同一事务内调用 {@link #enablePurge()},未打开清理通道时会被只追加触发器拒绝。</p>
     *
     * @param realmId 身份域主键
     * @param cutoff  留存期截止时间
     * @return 被删除的记录数
     */
    int deleteExpired(@Param("realmId") Long realmId,
                      @Param("cutoff") java.time.LocalDateTime cutoff);
}
