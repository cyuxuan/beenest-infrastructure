package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.NotificationLog;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知投递记录 Mapper(iam_notification_log,仅追加与查询)。
 * <p>查询排除已逻辑删除记录。</p>
 */
public interface NotificationLogMapper {

    /**
     * 插入一条投递记录,deleted 固定写入 FALSE。
     *
     * @param log 投递记录实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(NotificationLog log);

    /**
     * 查询域内最近若干条投递记录,按创建时间与主键倒序,用于排障。
     *
     * @param realmId 身份域主键
     * @param limit   返回条数上限
     * @return 投递记录列表;无记录时返回空列表
     */
    List<NotificationLog> selectRecent(@Param("realmId") Long realmId, @Param("limit") int limit);

    /**
     * 统计窗口内发往某收件地址的投递次数,用于找回口令限流。
     *
     * @param realmId   身份域主键
     * @param recipient 收件地址
     * @param minutes   统计窗口,单位为分钟
     * @return 投递次数
     */
    int countByRecipientSince(@Param("realmId") Long realmId,
                              @Param("recipient") String recipient,
                              @Param("minutes") int minutes);

    /**
     * 管理端按条件查询投递记录,按创建时间倒序。
     *
     * @param realmId      身份域主键
     * @param channel      渠道,为空不过滤
     * @param status       投递结果,为空不过滤
     * @param recipient    收件地址,支持模糊匹配
     * @param templateCode 模板编码,为空不过滤
     * @param fromTime     起始时间,为空不限制
     * @param toTime       截止时间,为空不限制
     * @return 投递记录列表;无匹配时返回空列表
     */
    List<NotificationLog> selectByCondition(@Param("realmId") Long realmId,
                                            @Param("channel") String channel,
                                            @Param("status") String status,
                                            @Param("recipient") String recipient,
                                            @Param("templateCode") String templateCode,
                                            @Param("fromTime") LocalDateTime fromTime,
                                            @Param("toTime") LocalDateTime toTime);

    /**
     * 物理删除创建时间早于截止时间的一批记录。
     * <p>按主键顺序限量删除,避免一次大事务长时间持锁;调用方循环直到返回
     * 行数小于批量大小。</p>
     *
     * @param cutoff 截止时间(不含该时刻之后的记录)
     * @param limit  单批删除上限
     * @return 实际删除行数
     */
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
