package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 客户端-作用域关联实体(iam_client_scope_mapping)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ClientScopeMapping extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 客户端主键 */
    private Long clientId;
    /** 作用域主键 */
    private Long scopeId;
}
