package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 单点登出广播记录实体(iam_logout_broadcast)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LogoutBroadcast extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 被结束的 IAM 会话标识 */
    private String sid;
    /** 用户主键 */
    private Long userId;
    /** 客户端主键 */
    private Long clientId;
    /** 客户端标识 */
    private String clientKey;
    /** 投递地址 */
    private String targetUri;
    /** 投递状态:PENDING 待投递 / SENT 已投递 / FAILED 失败 / SKIPPED 跳过 */
    private String state;
    /** 应用返回的 HTTP 状态码 */
    private Integer httpStatus;
    /** 失败原因 */
    private String errorMessage;
}
