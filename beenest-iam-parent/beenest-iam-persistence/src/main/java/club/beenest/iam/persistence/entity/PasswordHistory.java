package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 口令历史实体(iam_user_password_history),只追加。
 * <p>表无 updated_time 与 deleted 列,BaseEntity 的对应字段不使用。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class PasswordHistory extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键 */
    private Long userId;
    /** 历史口令哈希 */
    private String passwordHash;
}
