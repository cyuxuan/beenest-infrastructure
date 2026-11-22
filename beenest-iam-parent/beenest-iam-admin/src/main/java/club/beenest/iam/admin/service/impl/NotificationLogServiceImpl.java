package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.NotificationLogResponse;
import club.beenest.iam.admin.service.NotificationLogService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.NotificationLog;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 通知投递记录查询实现。
 * <p>记录按身份域过滤:投递失败的原因常带内部地址与通道错误,不该跨域可见。</p>
 */
@Service
public class NotificationLogServiceImpl implements NotificationLogService {

    private final IamNotificationLogManager notificationLogManager;

    public NotificationLogServiceImpl(IamNotificationLogManager notificationLogManager) {
        this.notificationLogManager = notificationLogManager;
    }

    @Override
    public PageResult<NotificationLogResponse> page(String channel, String status, String recipient,
                                                    String templateCode, LocalDateTime fromTime,
                                                    LocalDateTime toTime, int pageNum, int pageSize) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        PageResult<NotificationLog> page = notificationLogManager.page(realmId, channel, status, recipient,
                templateCode, fromTime, toTime, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    /**
     * 投递记录转响应。
     *
     * @param log 投递记录
     * @return 投递记录响应
     */
    private NotificationLogResponse toResponse(NotificationLog log) {
        return new NotificationLogResponse(
                log.getId(),
                log.getChannel(),
                log.getRecipient(),
                log.getTemplateCode(),
                log.getSubject(),
                log.getStatus(),
                log.getErrorMessage(),
                log.getUserId(),
                log.getCreatedTime());
    }
}
