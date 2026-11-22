package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户属性索引投影实体(iam_user_attribute_index)。
 * <p>仅 searchable/unique 属性写入,提供强唯一约束与属性维度检索;
 * 与 iam_user.attributes 在同事务内维护。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserAttributeIndex extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 属性键 */
    private String attrKey;
    /** 属性值(文本投影) */
    private String attrValue;
    /** 是否参与全局唯一约束(由属性定义冗余而来) */
    private Boolean isUnique;
}
