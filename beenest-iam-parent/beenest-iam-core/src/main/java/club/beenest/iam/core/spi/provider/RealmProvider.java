package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.RealmModel;

import java.util.List;
import java.util.Optional;

/**
 * 身份域存储 SPI。
 * <p>由持久层实现;authz 模块据此构建 per-realm 过滤器链与 issuer。</p>
 */
public interface RealmProvider {

    /**
     * 按 realmKey 查找身份域。
     *
     * @param realmKey issuer 路径段,如 default
     * @return 身份域;不存在时为空
     */
    Optional<RealmModel> findByRealmKey(String realmKey);

    /**
     * 按主键查找身份域。
     *
     * @param id 身份域主键
     * @return 身份域;不存在时为空
     */
    Optional<RealmModel> findById(Long id);

    /**
     * 列出全部启用中的身份域。
     * <p>启动时用于构建 per-realm 过滤器链。</p>
     *
     * @return 启用域列表;无记录返回空列表
     */
    List<RealmModel> listEnabled();
}
