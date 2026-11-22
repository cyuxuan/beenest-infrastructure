package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.ClientScopeCreateRequest;
import club.beenest.iam.admin.dto.ClientScopeResponse;
import club.beenest.iam.admin.dto.ClientScopeUpdateRequest;

import java.util.List;

/**
 * 客户端作用域管理服务。
 * <p>作用域限定在当前身份域且作用域键域内唯一。</p>
 */
public interface ClientScopeService {

    /**
     * 查询当前域全部作用域。
     *
     * @return 作用域列表
     */
    List<ClientScopeResponse> list();

    /**
     * 查询单个作用域。
     *
     * @param id 作用域主键
     * @return 作用域
     * @throws BusinessException 作用域不存在或不属于当前域
     */
    ClientScopeResponse get(Long id);

    /**
     * 新建作用域。
     *
     * @param request 作用域创建参数
     * @return 新建后的作用域
     * @throws BusinessException 作用域键在域内已存在
     */
    ClientScopeResponse create(ClientScopeCreateRequest request);

    /**
     * 更新作用域,仅覆盖请求中显式给出的字段。
     *
     * @param id      作用域主键
     * @param request 作用域更新参数
     * @return 更新后的作用域
     * @throws BusinessException 作用域不存在或不属于当前域
     */
    ClientScopeResponse update(Long id, ClientScopeUpdateRequest request);

    /**
     * 删除作用域,级联清理客户端关联。
     *
     * @param id 作用域主键
     * @throws BusinessException 作用域不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 查询客户端已分配的作用域主键。
     *
     * @param clientId 客户端主键
     * @return 已分配的作用域主键列表
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    List<Long> listClientScopeIds(Long clientId);

    /**
     * 覆盖式分配客户端作用域。
     * <p>客户端与作用域都必须属于当前域。</p>
     *
     * @param clientId 客户端主键
     * @param scopeIds 作用域主键集合
     * @throws BusinessException 客户端或作用域不存在、跨域
     */
    void assignClientScopes(Long clientId, List<Long> scopeIds);
}
