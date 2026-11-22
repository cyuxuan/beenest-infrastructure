package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 单点登出广播记录响应。
 *
 * @param id           主键
 * @param sid          被结束的会话标识
 * @param clientKey    客户端标识
 * @param targetUri    投递地址(为空表示应用未登记接收地址)
 * @param state        投递状态:PENDING / SENT / FAILED / SKIPPED
 * @param httpStatus   应用返回的 HTTP 状态码
 * @param errorMessage 失败原因
 * @param createdTime  记录时间
 */
public record LogoutBroadcastResponse(
        Long id,
        String sid,
        String clientKey,
        String targetUri,
        String state,
        Integer httpStatus,
        String errorMessage,
        LocalDateTime createdTime
) {
}
