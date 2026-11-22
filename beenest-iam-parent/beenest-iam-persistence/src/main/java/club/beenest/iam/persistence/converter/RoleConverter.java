package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.persistence.entity.Role;
import org.springframework.stereotype.Component;

/**
 * 角色实体/模型转换器。
 * <p>容器类型缺失或无法识别时按 REALM 处理。</p>
 */
@Component
public class RoleConverter {

    /**
     * 实体转换为领域模型。
     *
     * @param role 角色实体
     * @return 角色模型;入参为 null 时返回 null
     */
    public RoleModel toModel(Role role) {
        if (role == null) {
            return null;
        }
        RoleModel.ContainerType containerType = switch (role.getContainerType() == null
                ? "" : role.getContainerType().toUpperCase()) {
            case "APP" -> RoleModel.ContainerType.APP;
            case "CLIENT" -> RoleModel.ContainerType.CLIENT;
            default -> RoleModel.ContainerType.REALM;
        };
        return new RoleModel(
                role.getId(),
                role.getRealmId(),
                role.getRoleCode(),
                role.getRoleName(),
                containerType,
                role.getClientId(),
                role.getAppServiceId(),
                Boolean.TRUE.equals(role.getIsSystem())
        );
    }
}
