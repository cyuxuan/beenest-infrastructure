package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.LoginEventResponse;
import club.beenest.iam.common.result.PageResult;

/**
 * 登录事件查询服务,提供检索与 IP 溯源,只读。
 * <p>登录事件由认证链路旁路写入,仅追加不修改,查询按当前身份域隔离。</p>
 */
public interface LoginEventService {

    /**
     * 分页查询当前域登录事件,按时间倒序。
     *
     * @param username  登录名模糊过滤,可空
     * @param eventType 事件类型精确过滤,可空
     * @param ip        来源 IP 精确过滤,可空
     * @param fromTime  起始时间(含),可空
     * @param toTime    结束时间(含),可空
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 登录事件分页结果
     */
    PageResult<LoginEventResponse> page(String username, String eventType, String ip,
                                        java.time.LocalDateTime fromTime,
                                        java.time.LocalDateTime toTime,
                                        int pageNum, int pageSize);

    /**
     * 溯源某一来源地址的全部登录事件。
     * <p>用于排查同一 IP 对多个账号的失败尝试。</p>
     *
     * @param ip       来源 IP,精确匹配,必填
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     * @return 该 IP 的登录事件分页结果,按时间倒序
     * @throws BusinessException 来源 IP 为空
     */
    PageResult<LoginEventResponse> ipHistory(String ip, int pageNum, int pageSize);
}
