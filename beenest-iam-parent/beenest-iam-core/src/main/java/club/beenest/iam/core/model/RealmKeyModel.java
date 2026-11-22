package club.beenest.iam.core.model;

import java.time.LocalDateTime;

/**
 * 域签名密钥领域模型。
 * <p>每个域持有独立的 RSA 密钥对;active 行对应当前生效密钥,
 * 其余行为轮换期的旧密钥,仅发布公钥。</p>
 *
 * @param id            主键(iam_realm_key.id)
 * @param realmId       所属身份域
 * @param keyType       密钥类型,当前固定 RSA
 * @param privateKey    私钥(PEM)
 * @param publicKey     公钥(PEM)
 * @param active        是否当前生效
 * @param activatedTime 生效时间
 * @param retiredTime   退役时间
 */
public record RealmKeyModel(
        Long id,
        Long realmId,
        String keyType,
        String privateKey,
        String publicKey,
        boolean active,
        LocalDateTime activatedTime,
        LocalDateTime retiredTime
) {
}
