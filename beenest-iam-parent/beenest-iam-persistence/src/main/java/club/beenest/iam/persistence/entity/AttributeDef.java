package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户属性定义实体(iam_attribute_def)。
 * <p>声明属性类型、校验规则与索引要求,用于后台属性构建、用户资料校验与属性索引投影。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AttributeDef extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 属性键(域内唯一,如 department) */
    private String attrKey;
    /** 属性显示名 */
    private String attrName;
    /** 属性类型:STRING/NUMBER/BOOLEAN/DATE/ENUM/JSON */
    private String attrType;
    /** 是否必填 */
    private Boolean required;
    /** 是否可搜索(写入属性索引投影) */
    private Boolean searchable;
    /** 是否全局唯一(参与强唯一约束) */
    private Boolean uniqueFlag;
    /** 枚举可选值(ENUM 类型,逗号分隔) */
    private String enumOptions;
    /** 校验正则 */
    private String validationRegex;
    /** 说明 */
    private String description;
}
