package club.beenest.iam.core.model;

/**
 * 角色领域模型。
 * <p>域角色与应用角色统一建模:container_type 为 REALM 时为域角色,
 * 为 APP 时 appServiceId 指向所属应用;CLIENT 为兼容存量的客户端角色。</p>
 *
 * @param id            主键
 * @param realmId       所属身份域
 * @param roleCode      角色编码(写入令牌 realm_roles / client_roles)
 * @param roleName      展示名
 * @param containerType REALM / APP / CLIENT
 * @param clientId      CLIENT 类型的所属客户端主键
 * @param appServiceId  APP 类型的所属应用主键
 * @param system        是否系统内置(不可删除)
 */
public record RoleModel(
        Long id,
        Long realmId,
        String roleCode,
        String roleName,
        ContainerType containerType,
        Long clientId,
        Long appServiceId,
        boolean system
) {
    /** 角色容器类型: REALM 为域角色,APP 为应用角色,CLIENT 为客户端角色(兼容存量)。 */
    public enum ContainerType { REALM, APP, CLIENT }
}
