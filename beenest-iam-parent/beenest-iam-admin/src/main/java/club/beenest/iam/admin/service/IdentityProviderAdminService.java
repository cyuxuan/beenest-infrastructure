package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.IdpMapperCreateRequest;
import club.beenest.iam.admin.dto.IdpMapperResponse;
import club.beenest.iam.admin.dto.IdentityProviderCreateRequest;
import club.beenest.iam.admin.dto.IdentityProviderResponse;
import club.beenest.iam.admin.dto.IdentityProviderUpdateRequest;

import java.util.List;

/**
 * 身份提供方管理服务,维护联邦登录配置。
 * <p>配置限定在当前身份域;敏感配置项加密落库,响应只返回配置键名。</p>
 */
public interface IdentityProviderAdminService {

    /**
     * 查询当前域全部提供方。
     *
     * @return 提供方列表,配置项仅含键名
     */
    List<IdentityProviderResponse> list();

    /**
     * 查询单个提供方。
     *
     * @param id 提供方主键
     * @return 提供方配置
     * @throws BusinessException 提供方不存在或不属于当前域
     */
    IdentityProviderResponse get(Long id);

    /**
     * 新建提供方。
     * <p>别名域内唯一;启用状态必须配齐该类型的必填配置项。</p>
     *
     * @param request 提供方创建参数
     * @return 新建后的提供方
     * @throws BusinessException 别名重复或启用时必填配置项缺失
     */
    IdentityProviderResponse create(IdentityProviderCreateRequest request);

    /**
     * 更新提供方。
     * <p>机密项留空表示保留库中原值,非机密项整体覆盖;启用状态仍要求必填项齐备。</p>
     *
     * @param id      提供方主键
     * @param request 提供方更新参数
     * @return 更新后的提供方
     * @throws BusinessException 提供方不存在或合并后的配置不满足必填项
     */
    IdentityProviderResponse update(Long id, IdentityProviderUpdateRequest request);

    /**
     * 删除提供方,解绑外部身份并级联清理属性映射器。
     *
     * @param id 提供方主键
     * @throws BusinessException 提供方不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 查询提供方的属性映射器。
     *
     * @param idpId 提供方主键
     * @return 属性映射器列表
     * @throws BusinessException 提供方不存在或不属于当前域
     */
    List<IdpMapperResponse> listMappers(Long idpId);

    /**
     * 新建属性映射器。
     *
     * @param idpId   提供方主键
     * @param request 映射器创建参数,映射类型缺省为 ATTRIBUTE
     * @return 新建后的映射器
     * @throws BusinessException 提供方不存在或不属于当前域
     */
    IdpMapperResponse createMapper(Long idpId, IdpMapperCreateRequest request);

    /**
     * 删除属性映射器。
     *
     * @param idpId    提供方主键
     * @param mapperId 映射器主键
     * @throws BusinessException 提供方不存在,或映射器不属于该提供方
     */
    void deleteMapper(Long idpId, Long mapperId);
}
