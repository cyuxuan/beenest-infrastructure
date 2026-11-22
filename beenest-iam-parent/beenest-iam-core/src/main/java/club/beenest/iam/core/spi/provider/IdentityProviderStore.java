package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.IdpMapperModel;
import club.beenest.iam.core.model.IdentityProviderModel;

import java.util.List;
import java.util.Optional;

/**
 * 身份提供方存储 SPI。
 * <p>由持久层实现;broker 流程经此读取提供方配置、映射规则与用户绑定关系。</p>
 */
public interface IdentityProviderStore {

    /**
     * 按域与别名读取提供方。
     *
     * @param realmId 所属身份域
     * @param alias   提供方别名
     * @return 提供方(含主键与配置);不存在时为空
     */
    Optional<IdentityProviderModel> findByIdp(Long realmId, String alias);

    /**
     * 列出域内全部提供方。
     * <p>登录页据此渲染三方登录入口,未接入的提供方不出现在页面上。</p>
     *
     * @param realmId 所属身份域
     * @return 提供方列表;无记录返回空列表
     */
    List<IdentityProviderModel> listByRealm(Long realmId);

    /**
     * 查找外部身份已绑定的本地用户主键。
     * <p>三方登录回调时先查绑定,命中则直接复用本地账号。</p>
     *
     * @param idpId      身份提供方主键
     * @param externalId 外部身份唯一标识
     * @return 本地用户主键;未绑定时为空
     */
    Optional<Long> findLinkedUserId(Long idpId, String externalId);

    /**
     * 建立外部身份与本地用户的绑定。
     *
     * @param realmId          所属身份域
     * @param userId           本地用户主键
     * @param idpId            身份提供方主键
     * @param externalId       外部身份唯一标识
     * @param externalUsername 外部用户名
     */
    void link(Long realmId, Long userId, Long idpId, String externalId, String externalUsername);

    /**
     * 读取提供方的属性映射规则。
     * <p>回调时按这些规则把外部属性写入本地账号。</p>
     *
     * @param idpId 身份提供方主键
     * @return 映射规则列表;无规则返回空列表
     */
    List<IdpMapperModel> listMappers(Long idpId);

    /**
     * 解除提供方与本地账号的全部绑定。
     * <p>只解绑、不删账号:本地账号可能还有口令凭证或被其他渠道共用。</p>
     *
     * @param idpId 身份提供方主键
     * @return 被解绑的绑定条数
     */
    int unlinkByIdp(Long idpId);
}
