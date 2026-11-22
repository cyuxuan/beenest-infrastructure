package club.beenest.iam.core.model;

/**
 * 身份域(Realm)领域模型。
 * <p>Realm 是租户与隔离边界;每个 Realm 拥有独立 issuer(`/realms/{realmKey}`)与签名密钥。
 * 本模型为不可变快照,由持久层 Provider 构建。</p>
 *
 * @param id             主键
 * @param realmKey       issuer 路径段,如 default
 * @param realmName      展示名
 * @param enabled        是否启用
 * @param defaultRealm   是否默认域
 * @param accessTokenTtl  access token 有效期(秒)
 * @param refreshTokenTtl refresh token 有效期(秒)
 * @param ssoSessionIdle  会话空闲超时(秒)
 * @param ssoSessionMax   会话最大时长(秒)
 * @param subjectMode     本域默认的 OIDC sub 取值策略(客户端可单独覆盖)
 * @param theme           本域协议页主题目录名(NULL/空 = 跟随全局 iam.theme.active)
 */
public record RealmModel(
        Long id,
        String realmKey,
        String realmName,
        boolean enabled,
        boolean defaultRealm,
        long accessTokenTtl,
        long refreshTokenTtl,
        long ssoSessionIdle,
        long ssoSessionMax,
        SubjectMode subjectMode,
        String theme
) {
}
