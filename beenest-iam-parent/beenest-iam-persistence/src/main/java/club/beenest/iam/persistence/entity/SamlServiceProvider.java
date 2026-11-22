package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * SAML 服务方登记实体(iam_saml_service_provider)。
 * <p>本平台作为 IdP 签发断言时,按此确定受众、断言消费地址与属性映射。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SamlServiceProvider extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 站内别名,域内唯一 */
    private String spKey;
    /** SP 实体 ID */
    private String entityId;
    /** 展示名 */
    private String displayName;
    /** 断言消费地址 */
    private String acsUrl;
    /** 单点登出地址;空串表示不支持 SLO */
    private String sloUrl;
    /** NameID 格式 */
    private String nameIdFormat;
    /** 是否对断言签名 */
    private Boolean signAssertion;
    /** 是否强制要求 AuthnRequest 带签名 */
    private Boolean requireSignedAuthnRequest;
    /** SP 签名证书(PEM) */
    private String spCertificate;
    /** 属性映射 JSON 文本 */
    private String attributeMapping;
    /** 是否启用 */
    private Boolean enabled;
}
