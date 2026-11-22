package club.beenest.iam.authz.saml;

import java.time.Instant;

/**
 * 解析后的 SAML AuthnRequest。
 * <p>只保留签发断言与校验登记所需的字段;报文原文不留存,
 * 防重放靠请求 ID 的一次性消费。</p>
 *
 * @param id                           请求 ID,写入断言的 InResponseTo
 * @param issuer                       SP 实体 ID,用于反查登记
 * @param destination                  请求目标地址,应为本平台的单点登录端点
 * @param assertionConsumerServiceUrl  SP 指定的断言消费地址;为空时用登记项里的地址
 * @param nameIdFormat                 SP 要求的 NameID 格式;为空时用登记项里的格式
 * @param forceAuthn                   是否强制重新认证
 * @param issueInstant                 请求发起时刻;强制重认证据此判断会话是否够新
 * @param relayState                   原样回传的不透明串;为空表示 SP 未携带
 * @param signed                       报文内是否带 XML 签名
 */
public record SamlIdpAuthnRequest(
        String id,
        String issuer,
        String destination,
        String assertionConsumerServiceUrl,
        String nameIdFormat,
        boolean forceAuthn,
        Instant issueInstant,
        String relayState,
        boolean signed
) {
}
