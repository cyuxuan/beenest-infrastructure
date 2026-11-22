package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.NotificationLogResponse;
import club.beenest.iam.admin.service.NotificationLogService;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 通知投递记录查询接口(只读)。
 * <p>邮件与短信投递只追加记录,不提供修改与删除:排障时需要看到的就是原始结果,
 * 允许改写会让"投递失败"这件事失去可信度。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/notification-logs")
public class NotificationLogController {

    private final NotificationLogService notificationLogService;

    public NotificationLogController(NotificationLogService notificationLogService) {
        this.notificationLogService = notificationLogService;
    }

    /**
     * 分页查询投递记录,按时间倒序。
     *
     * @param channel      渠道:EMAIL / SMS(可选)
     * @param status       投递结果:SENT / FAILED(可选)
     * @param recipient    收件地址模糊过滤(可选)
     * @param templateCode 模板编码精确过滤(可选)
     * @param fromTime     起始时间(可选,ISO 格式)
     * @param toTime       结束时间(可选,ISO 格式)
     * @param pageNum      页码
     * @param pageSize     每页条数
     * @return 当前域投递记录分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('notification:view')")
    public Result<PageResult<NotificationLogResponse>> page(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toTime,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(notificationLogService.page(
                channel, status, recipient, templateCode, fromTime, toTime, pageNum, pageSize));
    }
}
