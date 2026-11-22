package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 域级口令策略实体(iam_password_policy,每域一行)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PasswordPolicy extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 最小长度 */
    private Integer minLength;
    /** 最大长度 */
    private Integer maxLength;
    /** 要求大写字母 */
    private Boolean requireUppercase;
    /** 要求小写字母 */
    private Boolean requireLowercase;
    /** 要求数字 */
    private Boolean requireDigit;
    /** 要求特殊符号 */
    private Boolean requireSymbol;
    /** 禁止重复使用最近 N 个口令(0 关闭) */
    private Integer historyCount;
    /** 口令有效期天数(0 永不过期) */
    private Integer expireDays;
}
