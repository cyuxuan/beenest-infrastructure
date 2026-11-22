package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.SamlServiceProviderModel;

import java.util.List;
import java.util.Optional;

/**
 * SAML 服务方(SP)登记存储 SPI。
 * <p>由持久层实现;IdP 端点据此把 AuthnRequest 的 Issuer 解析为登记项,
 * 管理接口据此维护登记。</p>
 */
public interface SamlServiceProviderStore {

    /**
     * 按 SP 实体 ID 查找登记。
     * <p>单点登录入口按 AuthnRequest 的 Issuer 反查,这是主查询路径。</p>
     *
     * @param realmId  所属身份域
     * @param entityId SP 实体 ID
     * @return 登记;不存在或已删除时为空
     */
    Optional<SamlServiceProviderModel> findByEntityId(Long realmId, String entityId);

    /**
     * 按站内别名查找登记。
     *
     * @param realmId 所属身份域
     * @param spKey   站内别名
     * @return 登记;不存在或已删除时为空
     */
    Optional<SamlServiceProviderModel> findByKey(Long realmId, String spKey);

    /**
     * 按主键查找登记。
     *
     * @param realmId 所属身份域,用于限定查找范围
     * @param id      登记主键
     * @return 登记;不存在、已删除或不属于该域时为空
     */
    Optional<SamlServiceProviderModel> findById(Long realmId, Long id);

    /**
     * 列出域内全部登记。
     *
     * @param realmId 所属身份域
     * @return 登记列表;无记录返回空列表
     */
    List<SamlServiceProviderModel> listByRealm(Long realmId);

    /**
     * 保存登记:主键为空时插入,否则按主键更新。
     *
     * @param model 登记模型
     * @return 保存后的主键
     */
    Long save(SamlServiceProviderModel model);

    /**
     * 逻辑删除登记。
     *
     * @param realmId 所属身份域,用于限定删除范围
     * @param id      登记主键
     * @return 受影响行数;登记不存在或不属于该域时为 0
     */
    int delete(Long realmId, Long id);
}
