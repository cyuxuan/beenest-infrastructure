package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.NotificationLog;
import club.beenest.iam.persistence.mapper.NotificationLogMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知投递记录存储(iam_notification_log)。
 * <p>只追加;错误消息按 500 字符截断,排障查询最多返回 200 条。</p>
 */
@Component
public class IamNotificationLogManager {

    /** 状态:已发送 */
    public static final String STATUS_SENT = "SENT";
    /** 状态:发送失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 渠道:邮件 */
    public static final String CHANNEL_EMAIL = "EMAIL";
    /** 渠道:短信 */
    public static final String CHANNEL_SMS = "SMS";

    private final NotificationLogMapper mapper;

    public IamNotificationLogManager(NotificationLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录一次投递。
     *
     * @param realmId 身份域主键
     * @param channel 渠道(EMAIL / SMS)
     * @param recipient 收件地址
     * @param templateCode 模板编码
     * @param subject 主题
     * @param status 状态(SENT / FAILED)
     * @param errorMessage 失败原因,超过 500 字符截断
     * @param userId 关联用户主键,可为空
     */
    public void record(Long realmId, String channel, String recipient, String templateCode,
                       String subject, String status, String errorMessage, Long userId) {
        NotificationLog log = new NotificationLog();
        log.setRealmId(realmId);
        log.setChannel(channel);
        log.setRecipient(recipient);
        log.setTemplateCode(templateCode);
        log.setSubject(subject);
        log.setStatus(status);
        // 错误消息列长度 500,超长截断而不是让整条记录写不进去
        log.setErrorMessage(truncate(errorMessage, 500));
        log.setUserId(userId);
        mapper.insert(log);
    }

    /**
     * 最近若干条投递记录(排障用)。
     *
     * @param realmId 身份域主键
     * @param limit 条数,限定在 1 到 200 之间
     * @return 投递记录,按主键倒序
     */
    public List<NotificationLog> recent(Long realmId, int limit) {
        return mapper.selectRecent(realmId, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * 收件地址在最近 N 分钟内的投递次数。
     *
     * @param realmId 身份域主键
     * @param recipient 收件地址
     * @param minutes 时间窗口(分钟)
     * @return 投递次数
     */
    public int countRecent(Long realmId, String recipient, int minutes) {
        return mapper.countByRecipientSince(realmId, recipient, minutes);
    }

    /**
     * 删除创建时间早于截止时间的一批记录。
     * <p>投递记录是排障用的运行数据,不是审计证据:审计以 iam_audit_log 与
     * iam_login_event 为准,那些表另有留存策略。此表若不清理会随发信量单调增长。</p>
     *
     * @param cutoff 截止时间
     * @param limit  单批上限,限定在 1 到 10000 之间
     * @return 实际删除行数
     */
    public int deleteBefore(LocalDateTime cutoff, int limit) {
        return mapper.deleteBefore(cutoff, Math.max(1, Math.min(limit, 10000)));
    }

    /**
     * 截断超长文本。
     *
     * @param value 原文本
     * @param max 最大长度
     * @return 截断后的文本;原文本为 null 时返回 null
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 管理端分页查询投递记录。
     *
     * @param realmId      身份域主键
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
    public PageResult<NotificationLog> page(Long realmId, String channel, String status, String recipient,
                                            String templateCode, LocalDateTime fromTime, LocalDateTime toTime,
                                            int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<NotificationLog> list = mapper.selectByCondition(
                realmId, channel, status, recipient, templateCode, fromTime, toTime);
        PageInfo<NotificationLog> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }
}
