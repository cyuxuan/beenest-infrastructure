package club.beenest.iam.authz.saml;

/**
 * 身份域下 SAML IdP 的三个端点地址。
 * <p>实体 ID 取元数据地址:SP 侧登记时填的就是它,换域名必须同步改登记,
 * 因此不能随请求头漂移——由调用方按固定基础地址推导。</p>
 *
 * @param entityId  IdP 实体 ID(元数据地址)
 * @param ssoUrl    单点登录端点
 * @param sloUrl    单点登出端点
 */
public record SamlIdpEndpoints(String entityId, String ssoUrl, String sloUrl) {

    /** 端点路径前缀:与协议端点同处 /realms/{realmKey} 之下 */
    private static final String BASE_PATH = "/realms/%s/saml";

    /**
     * 按基础地址与身份域推导端点。
     *
     * @param origin   站点源地址,形如 {@code https://iam.example.com}
     * @param realmKey 身份域标识
     * @return 端点集合
     */
    public static SamlIdpEndpoints of(String origin, String realmKey) {
        String base = origin + String.format(BASE_PATH, realmKey);
        return new SamlIdpEndpoints(base + "/metadata", base + "/sso", base + "/slo");
    }
}
