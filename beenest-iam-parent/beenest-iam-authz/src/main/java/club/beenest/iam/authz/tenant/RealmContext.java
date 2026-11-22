package club.beenest.iam.authz.tenant;

import club.beenest.iam.core.model.RealmModel;

/**
 * 身份域上下文(请求期)。
 * <p>协议链按路径 /realms/{realmKey} 解析;登录页/登录提交按 realm 参数解析。
 * 下游(JWKSource、客户端仓储、令牌定制)经 {@link RealmContextHolder} 读取。</p>
 *
 * @param realmId   身份域主键
 * @param realmKey  身份域标识(issuer 路径段)
 * @param realm     身份域模型(供 TTL 等参数读取)
 */
public record RealmContext(Long realmId, String realmKey, RealmModel realm) {
}
