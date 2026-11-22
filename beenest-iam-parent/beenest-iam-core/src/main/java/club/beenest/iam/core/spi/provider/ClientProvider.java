package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.ClientModel;

import java.util.Optional;

/**
 * 客户端存储 SPI。
 * <p>由持久层实现;authz 模块据此构建 Spring Authorization Server 的 RegisteredClient。</p>
 */
public interface ClientProvider {

    /**
     * 按 clientKey 与身份域查找客户端。
     *
     * @param realmId   所属身份域
     * @param clientKey 客户端标识
     * @return 客户端;不存在时为空
     */
    Optional<ClientModel> findByClientKey(Long realmId, String clientKey);

    /**
     * 按主键查找客户端。
     *
     * @param id 客户端主键
     * @return 客户端;不存在时为空
     */
    Optional<ClientModel> findById(Long id);
}
