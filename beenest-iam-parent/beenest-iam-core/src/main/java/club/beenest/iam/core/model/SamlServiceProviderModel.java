package club.beenest.iam.core.model;

import java.util.Map;

/**
 * SAML 服务方(SP)登记模型。
 * <p>本平台作为身份提供方(IdP)时,域内需要向本平台发起单点登录的下游应用
 * 按此登记;一行对应一个 SP 实体。</p>
 *
 * @param id                        登记主键(iam_saml_service_provider.id)
 * @param realmId                   所属身份域
 * @param spKey                     域内唯一别名,仅管理接口与日志使用,不出现在报文里
 * @param entityId                  SP 实体 ID,用作断言的 AudienceRestriction
 * @param displayName               展示名
 * @param acsUrl                    断言消费地址,用作断言的 Recipient
 * @param sloUrl                    单点登出地址;为空表示该 SP 不支持 SLO
 * @param nameIdFormat              NameID 格式;SP 在 AuthnRequest 中指定时以 SP 为准
 * @param signAssertion             是否对断言签名
 * @param requireSignedAuthnRequest 是否强制要求 AuthnRequest 带签名
 * @param spCertificate             SP 签名证书(PEM),用于校验 AuthnRequest 签名
 * @param attributeMapping          属性映射:断言属性名 → 取值表达式
 * @param enabled                   是否启用;停用后该 SP 的登录请求一律拒绝
 */
public record SamlServiceProviderModel(
        Long id,
        Long realmId,
        String spKey,
        String entityId,
        String displayName,
        String acsUrl,
        String sloUrl,
        String nameIdFormat,
        boolean signAssertion,
        boolean requireSignedAuthnRequest,
        String spCertificate,
        Map<String, String> attributeMapping,
        boolean enabled
) {

    /**
     * 是否登记了可用于验签的 SP 证书。
     *
     * @return 证书非空时为 true
     */
    public boolean hasCertificate() {
        return spCertificate != null && !spCertificate.isBlank();
    }

    /**
     * 是否支持单点登出。
     *
     * @return 登记了登出地址时为 true
     */
    public boolean supportsSlo() {
        return sloUrl != null && !sloUrl.isBlank();
    }
}
