package club.beenest.iam.core.model;

import java.time.LocalDateTime;

/**
 * 单点登出广播记录。
 * <p>每向接入应用投递一次登出通知留一条记录,保存投递目标、状态与失败原因,
 * 供会话管理与排障查询。</p>
 *
 * @param id           主键
 * @param realmId      所属身份域
 * @param sid          被结束的 IAM 会话标识
 * @param userId       用户主键
 * @param clientId     客户端主键
 * @param clientKey    客户端标识
 * @param targetUri    投递地址(应用登记的后通道登出地址)
 * @param state        投递状态:PENDING / SENT / FAILED / SKIPPED
 * @param httpStatus   应用返回的 HTTP 状态码
 * @param errorMessage 失败原因
 * @param createdTime  记录时间
 */
public record LogoutBroadcastModel(
        Long id,
        Long realmId,
        String sid,
        Long userId,
        Long clientId,
        String clientKey,
        String targetUri,
        String state,
        Integer httpStatus,
        String errorMessage,
        LocalDateTime createdTime
) {

    /** 待投递(记录已落库,尚未发出) */
    public static final String STATE_PENDING = "PENDING";
    /** 已投递(应用返回 2xx) */
    public static final String STATE_SENT = "SENT";
    /** 投递失败(网络异常或非 2xx) */
    public static final String STATE_FAILED = "FAILED";
    /** 未投递(应用未登记接收地址) */
    public static final String STATE_SKIPPED = "SKIPPED";

    /**
     * 构建待落库的新记录。
     *
     * @param realmId   所属身份域
     * @param sid       被结束的 IAM 会话标识
     * @param userId    用户主键
     * @param clientId  客户端主键
     * @param clientKey 客户端标识
     * @param targetUri 投递地址
     * @return 状态为 PENDING、id 与时间为空的记录
     */
    public static LogoutBroadcastModel pending(Long realmId, String sid, Long userId, Long clientId,
                                               String clientKey, String targetUri) {
        return new LogoutBroadcastModel(null, realmId, sid, userId, clientId, clientKey,
                targetUri, STATE_PENDING, null, null, null);
    }
}
