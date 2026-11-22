package club.beenest.iam.authz.saml;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 待签发的断言内容。
 * <p>由调用方按登记项与当前登录态填好,协议层只负责编组与签名,
 * 不做任何身份判断。</p>
 *
 * @param issuer               IdP 实体 ID,写入 Response 与 Assertion 的 Issuer
 * @param audience             SP 实体 ID,写入 AudienceRestriction
 * @param acsUrl               断言消费地址,写入 Destination 与 Recipient
 * @param inResponseTo         对应的 AuthnRequest ID;IdP 发起时为空
 * @param nameId               主体标识(登录名)
 * @param nameIdFormat         NameID 格式;为空按 unspecified 处理
 * @param sessionIndex         会话索引,登出时按它定位会话
 * @param sessionNotOnOrAfter  会话失效时间;为空表示不设上限
 * @param attributes           断言属性:属性名 → 值列表
 * @param signAssertion        是否对断言签名
 */
public record SamlIdpAssertionSpec(
        String issuer,
        String audience,
        String acsUrl,
        String inResponseTo,
        String nameId,
        String nameIdFormat,
        String sessionIndex,
        Instant sessionNotOnOrAfter,
        Map<String, List<String>> attributes,
        boolean signAssertion
) {
}
