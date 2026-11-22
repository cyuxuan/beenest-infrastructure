package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.RealmKeyModel;

import java.util.List;
import java.util.Optional;

/**
 * 域签名密钥存储 SPI。
 * <p>由持久层实现;authz 据此构建每域独立的 JWKSource。</p>
 */
public interface RealmKeyProvider {

    /**
     * 当前生效密钥。
     *
     * @param realmId 所属身份域
     * @return 生效密钥;每域至多一条 active 行,未生成时为空
     */
    Optional<RealmKeyModel> findActive(Long realmId);

    /**
     * 列出域内全部未删除密钥。
     * <p>含轮换期旧密钥,JWKS 同时发布其公钥。</p>
     *
     * @param realmId 所属身份域
     * @return 密钥列表;无记录返回空列表
     */
    List<RealmKeyModel> listByRealm(Long realmId);

    /**
     * 保存密钥。
     *
     * @param realmKey 待保存的密钥,首次生成或轮换时调用
     */
    void save(RealmKeyModel realmKey);

    /**
     * 退役域内全部生效密钥。
     * <p>旧密钥保留记录并写入退役时间,JWKS 继续发布其公钥,
     * 已签发令牌在自然过期前仍可验签。</p>
     *
     * @param realmId 所属身份域
     */
    void deactivateByRealm(Long realmId);
}
