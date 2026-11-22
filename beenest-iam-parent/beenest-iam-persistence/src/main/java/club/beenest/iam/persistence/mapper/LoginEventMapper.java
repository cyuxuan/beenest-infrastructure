package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.LoginEvent;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 登录事件 Mapper(iam_login_event,仅追加与查询)。
 * <p>查询排除已逻辑删除事件。</p>
 */
public interface LoginEventMapper {

    /**
     * 插入登录事件,发生时间为空时取当前时间。
     *
     * @param loginEvent 登录事件实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(LoginEvent loginEvent);

    /**
     * 管理端分页查询,按发生时间与主键倒序。
     * <p>过滤维度:登录名模糊匹配,事件类型、IP 与时间范围精确或区间匹配。</p>
     *
     * @param realmId   身份域主键
     * @param username  登录名,模糊匹配
     * @param eventType 事件类型
     * @param ip        客户端 IP,精确匹配
     * @param fromTime  起始时间(含)
     * @param toTime    截止时间(含)
     * @return 事件列表;无匹配时返回空列表
     */
    List<LoginEvent> selectByCondition(@Param("realmId") Long realmId,
                                       @Param("username") String username,
                                       @Param("eventType") String eventType,
                                       @Param("ip") String ip,
                                       @Param("fromTime") LocalDateTime fromTime,
                                       @Param("toTime") LocalDateTime toTime);

    /**
     * 按事件类型汇总指定时间点起的事件数。
     *
     * @param realmId 身份域主键
     * @param since   起始时间(含)
     * @return 每行含事件类型与数量;无事件时返回空列表
     */
    List<Map<String, Object>> countByTypeSince(@Param("realmId") Long realmId,
                                               @Param("since") LocalDateTime since);
}
