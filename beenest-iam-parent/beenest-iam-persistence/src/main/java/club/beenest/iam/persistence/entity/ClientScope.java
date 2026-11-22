package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 客户端作用域实体(iam_client_scope)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ClientScope extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 作用域键(域内唯一,如 email) */
    private String scopeKey;
    /** 作用域显示名 */
    private String scopeName;
    /** 是否为默认作用域(新客户端自动携带) */
    private Boolean isDefault;
}
