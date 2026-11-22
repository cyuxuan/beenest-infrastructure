package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 用户实体(iam_user)。
 * <p>账号按域隔离,用户名域内唯一;tokenVersion 自增即令该用户已签发的令牌全部失效。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class User extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户名(域内唯一) */
    private String username;
    /** 昵称 */
    private String nickname;
    /** 邮箱 */
    private String email;
    /** 手机号 */
    private String phone;
    /** 手机号已验证;仅已验证手机号可用于短信验证码登录 */
    private Boolean phoneVerified;
    /** 状态:1正常 0禁用 2锁定 */
    private Integer status;
    /** 用户类型:INTERNAL 内部 / EXTERNAL 外部 / SERVICE 服务 */
    private String userType;
    /** 邮箱已验证 */
    private Boolean emailVerified;
    /** 令牌版本,自增即令该用户已签发的令牌全部失效 */
    private Integer tokenVersion;
    /** 登录后必须完成的事项,逗号分隔;空串表示无待办 */
    private String requiredActions;
    /** 扩展属性(JSONB 键值对象,属性真值源) */
    private String attributes;
}
