package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.NotificationLogResponse;
import club.beenest.iam.common.result.PageResult;

import java.time.LocalDateTime;

/**
 * 通知投递记录查询服务。
 */
public interface NotificationLogService {

    /**
     * 分页查询当前身份域的投递记录,按时间倒序。
     *
     * @param channel      渠道,为空不过滤
     * @param status       投递结果,为空不过滤
     * @param recipient    收件地址,为空不过滤,非空时模糊匹配
     * @param templateCode 模板编码,为空不过滤
     * @param fromTime     起始时间,为空不限制
     * @param toTime       截止时间,为空不限制
     * @param pageNum      页码
     * @param pageSize     每页条数
     * @return 投递记录分页结果
     */
    PageResult<NotificationLogResponse> page(String channel, String status, String recipient,
                                             String templateCode, LocalDateTime fromTime, LocalDateTime toTime,
                                             int pageNum, int pageSize);
}
