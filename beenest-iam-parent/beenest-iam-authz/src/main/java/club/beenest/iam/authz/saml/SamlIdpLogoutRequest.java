package club.beenest.iam.authz.saml;

/**
 * 解析后的 SAML LogoutRequest。
 *
 * @param id           请求 ID,写入 LogoutResponse 的 InResponseTo
 * @param issuer       SP 实体 ID,用于反查登记
 * @param nameId       待登出的主体标识;为空表示 SP 未指定
 * @param sessionIndex 待登出的会话索引;为空表示登出该主体的全部会话
 * @param signed       报文内是否带 XML 签名
 */
public record SamlIdpLogoutRequest(
        String id,
        String issuer,
        String nameId,
        String sessionIndex,
        boolean signed
) {
}
