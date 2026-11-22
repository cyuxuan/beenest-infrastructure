package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.MenuUpdateRequest;

import java.util.List;

/**
 * 菜单管理服务。
 * <p>菜单按应用归属:管理面按应用维护菜单树,角色授予菜单即派生菜单上挂载的权限。</p>
 */
public interface MenuService {

    /**
     * 查询应用在菜单托管范围内的全部菜单,扁平列表,含停用项。
     *
     * @param appServiceId 应用服务主键
     * @return 菜单列表
     * @throws BusinessException 应用不存在或不属于当前域
     */
    List<MenuResponse> listByApp(Long appServiceId);

    /**
     * 查询单个菜单。
     *
     * @param id 菜单主键
     * @return 菜单详情
     * @throws BusinessException 菜单不存在或不属于当前域
     */
    MenuResponse get(Long id);

    /**
     * 在应用下新建菜单或按钮。
     * <p>父菜单必须与该菜单同属一个应用;菜单编码在应用内唯一;挂载的权限必须属于本域且与本应用同命名空间。</p>
     *
     * @param request 菜单创建参数
     * @return 新建后的菜单
     * @throws BusinessException 应用不存在、父菜单或权限不属于该应用或菜单编码重复
     */
    MenuResponse create(MenuCreateRequest request);

    /**
     * 更新菜单,仅覆盖请求中显式给出的字段;父级与编码不可修改。
     *
     * @param id      菜单主键
     * @param request 菜单更新参数
     * @return 更新后的菜单
     * @throws BusinessException 菜单不存在或挂载的权限不属于该应用
     */
    MenuResponse update(Long id, MenuUpdateRequest request);

    /**
     * 逻辑删除菜单及其子项,并清理相关角色-菜单关联。
     *
     * @param id 菜单主键
     * @throws BusinessException 菜单不存在或不属于当前域
     */
    void delete(Long id);
}
