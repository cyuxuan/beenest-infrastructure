package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户-外部身份绑定实体(iam_user_federated_identity)。
 * <p>记录 IdP 侧 subject 与本地用户的对应关系,同一 IdP 下 subject 唯一,
 * 同一用户在同一 IdP 下仅一条绑定。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserFederatedIdentity extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 身份提供方主键 */
    private Long idpId;
    /** IdP 侧 subject */
    private String externalId;
    /** IdP 侧用户名 */
    private String externalUsername;
}
