package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 客户端会话实体(iam_client_session)。
 * <p>用户会话在某接入应用上的一次登录记录,作为单点登出广播的寻址依据。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ClientSession extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户会话记录主键(iam_user_session.id) */
    private Long userSessionId;
    /** 客户端主键(iam_client.id) */
    private Long clientId;

    /** 联查字段:会话标识(iam_user_session.sid),写入时不由本表维护 */
    private String sid;
    /** 联查字段:用户主键,写入时不由本表维护 */
    private Long userId;
    /** 联查字段:客户端标识(iam_client.client_key),写入时不由本表维护 */
    private String clientKey;
}
