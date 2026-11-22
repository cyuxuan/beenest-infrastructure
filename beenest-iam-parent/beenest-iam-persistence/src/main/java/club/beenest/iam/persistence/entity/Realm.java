package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 身份域实体(iam_realm)。
 * <p>每个域独立 issuer 与签名密钥,realmKey 作为 issuer 路径段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Realm extends BaseEntity {

    /** 域标识(issuer 路径段) */
    private String realmKey;
    /** 域名称 */
    private String realmName;
    /** 是否启用 */
    private Boolean enabled;
    /** 是否默认域 */
    private Boolean isDefault;
    /** access token 有效期(秒) */
    private Long accessTokenTtl;
    /** refresh token 有效期(秒) */
    private Long refreshTokenTtl;
    /** 会话空闲超时(秒) */
    private Long ssoSessionIdle;
    /** 会话最大时长(秒) */
    private Long ssoSessionMax;
    /** OIDC sub 取值策略:LOGIN_NAME 登录名 / USER_ID 不可变用户主键 */
    private String subjectMode;
    /** 本域协议页主题目录名(NULL/空 = 跟随全局 iam.theme.active) */
    private String theme;
}
