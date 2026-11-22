package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.persistence.entity.Role;
import org.springframework.stereotype.Component;

/**
 * 角色管理端对象转换器。
 * <p>负责请求、实体、响应之间的字段搬运;容器归属的合法性校验在服务层完成。</p>
 */
@Component
public class RoleDtoConverter {

    /** 容器类型:域级角色 */
    private static final String CONTAINER_REALM = "REALM";
    /** 容器类型:客户端角色(兼容存量) */
    private static final String CONTAINER_CLIENT = "CLIENT";

    /**
     * 角色实体转响应。
     *
     * @param role 角色实体,可为 null
     * @return 角色响应,入参为 null 时返回 null
     */
    public RoleResponse toResponse(Role role) {
        if (role == null) {
            return null;
        }
        return new RoleResponse(
                role.getId(),
                role.getRealmId(),
                role.getRoleCode(),
                role.getRoleName(),
                role.getContainerType(),
                role.getClientId(),
                role.getAppServiceId(),
                role.getIsSystem(),
                role.getIsActive(),
                role.getCreatedTime(),
                role.getUpdatedTime());
    }

    /**
     * 新建请求转实体。
     * <p>容器类型缺省为域级;新建角色一律非内置且启用;域级角色不挂应用与客户端,
     * APP 与 CLIENT 的归属由服务层校验后写入。</p>
     *
     * @param request 新建请求
     * @param realmId 身份域主键
     * @return 角色实体
     */
    public Role toEntity(RoleCreateRequest request, Long realmId) {
        String containerType = request.containerType() == null || request.containerType().isBlank()
                ? CONTAINER_REALM : request.containerType().toUpperCase();
        Role role = new Role();
        role.setRealmId(realmId);
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName());
        role.setContainerType(containerType);
        // 域级角色不挂应用/客户端;APP 与 CLIENT 的归属由服务层校验后写入
        role.setClientId(CONTAINER_CLIENT.equals(containerType) ? request.clientId() : null);
        role.setAppServiceId(CONTAINER_REALM.equals(containerType) ? null : request.appServiceId());
        role.setIsSystem(false);
        role.setIsActive(true);
        return role;
    }

    /**
     * 将更新请求中的非空字段写入实体。
     * <p>只写名称与启用状态:角色编码与容器归属不参与通用更新,归属变更由服务层
     * 单独校验并落库。</p>
     *
     * @param role    库中角色实体
     * @param request 更新请求
     */
    public void applyUpdate(Role role, RoleUpdateRequest request) {
        if (request.roleName() != null) {
            role.setRoleName(request.roleName());
        }
        if (request.isActive() != null) {
            role.setIsActive(request.isActive());
        }
    }
}
