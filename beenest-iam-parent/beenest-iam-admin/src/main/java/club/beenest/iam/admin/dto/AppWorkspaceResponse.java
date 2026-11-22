package club.beenest.iam.admin.dto;

/**
 * 应用管理工作台概览。
 * <p>仅包含应用管理员权限范围内的数据;接入方式、可见性、令牌档等全局配置由全局管理员维护。</p>
 *
 * @param app            应用基本信息
 * @param menuCount      菜单节点数
 * @param roleCount      应用角色数(含已绑定该应用的客户端角色)
 * @param permissionCount 应用命名空间内的权限数
 * @param mappingCount   角色映射数
 * @param adminCount     应用管理员数
 * @param globalManage   当前账号是否具备全局应用管理权限
 */
public record AppWorkspaceResponse(
        ApplicationServiceResponse app,
        int menuCount,
        int roleCount,
        int permissionCount,
        int mappingCount,
        int adminCount,
        boolean globalManage
) {
}
