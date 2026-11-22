package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.common.result.PageResult;

/**
 * 权限目录服务。
 * <p>平台权限由初始化脚本随功能发布定义;应用权限由管理员在应用命名空间内维护。
 * 权限码创建后不可修改,删除前必须确认未被角色、菜单或用户特批引用。</p>
 */
public interface PermissionService {

    /**
     * 分页查询当前域权限。
     *
     * @param permissionCode 权限编码模糊匹配,可空
     * @param permissionName 权限名称模糊匹配,可空
     * @param resource       资源精确匹配,可空
     * @param appServiceId   所属应用主键,可空,不传表示全部命名空间
     * @param pageNum        页码,从 1 开始
     * @param pageSize       每页条数
     * @return 权限分页结果
     */
    PageResult<PermissionResponse> page(String permissionCode, String permissionName,
                                        String resource, Long appServiceId, int pageNum, int pageSize);

    /**
     * 查询单个权限。
     *
     * @param id 权限主键
     * @return 权限详情
     * @throws BusinessException 权限不存在或不属于当前域
     */
    PermissionResponse get(Long id);

    /**
     * 新建权限,按应用主键区分平台权限与应用权限。
     * <p>权限编码在同命名空间内唯一,创建后不可修改。</p>
     *
     * @param request 权限创建参数
     * @return 新建后的权限
     * @throws BusinessException 权限编码重复或所属应用不合法
     */
    PermissionResponse create(PermissionCreateRequest request);

    /**
     * 更新权限的名称与说明,权限编码与归属不可修改。
     *
     * @param id      权限主键
     * @param request 权限更新参数
     * @return 更新后的权限
     * @throws BusinessException 权限不存在或不属于当前域
     */
    PermissionResponse update(Long id, PermissionUpdateRequest request);

    /**
     * 逻辑删除权限。
     * <p>被角色、菜单或用户特批引用的权限不允许删除。</p>
     *
     * @param id 权限主键
     * @throws BusinessException 权限不存在、不属于当前域或仍被引用
     */
    void delete(Long id);
}
