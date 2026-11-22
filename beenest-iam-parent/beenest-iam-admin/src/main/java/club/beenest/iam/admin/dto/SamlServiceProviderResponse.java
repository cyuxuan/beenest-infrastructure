package club.beenest.iam.admin.dto;

import java.util.Map;

/**
 * SAML 服务方登记响应。
 *
 * @param id                        登记主键
 * @param spKey                     站内别名
 * @param entityId                  SP 实体 ID
 * @param displayName               展示名
 * @param acsUrl                    断言消费地址
 * @param sloUrl                    单点登出地址
 * @param nameIdFormat              NameID 格式
 * @param signAssertion             是否对断言签名
 * @param requireSignedAuthnRequest 是否强制要求 AuthnRequest 带签名
 * @param certificateConfigured     是否已登记 SP 签名证书
 * @param spCertificate             SP 签名证书(PEM);属公开材料,原样回显便于核对
 * @param attributeMapping          属性映射
 * @param enabled                   是否启用
 */
public record SamlServiceProviderResponse(
        Long id,
        String spKey,
        String entityId,
        String displayName,
        String acsUrl,
        String sloUrl,
        String nameIdFormat,
        boolean signAssertion,
        boolean requireSignedAuthnRequest,
        boolean certificateConfigured,
        String spCertificate,
        Map<String, String> attributeMapping,
        boolean enabled
) {
}
