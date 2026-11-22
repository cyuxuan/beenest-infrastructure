package club.beenest.iam.core.spi.provider;

import java.util.Set;

/**
 * 权限计算 SPI。
 * <p>由持久层实现,公式:最终权限 = (角色-菜单派生 ∪ 角色直接权限 ∪ 用户 GRANT) − 用户 REVOKE;
 * 过期的 GRANT/REVOKE 均不生效。</p>
 */
public interface PermissionProvider {

    /**
     * 计算用户在某身份域下的最终权限码集合。
     *
     * @param realmId 身份域
     * @param userId  用户主键
     * @return 权限码集合(resource:action 形式)
     */
    Set<String> computePermissionCodes(Long realmId, Long userId);

    /**
     * 当前权限代次。
     * <p>任何影响权限的变更都使其自增;登录时把代次记在主体上,
     * 会话据此判断手里的权限是否需要重算。</p>
     *
     * @param realmId 身份域
     * @return 当前代次
     */
    long currentPermissionEpoch(Long realmId);

    /**
     * 计算用户在某应用命名空间内的最终权限码集合。
     * <p>平台权限不参与业务应用权限集;菜单派生只接受该应用下的启用菜单。</p>
     *
     * @param realmId      身份域
     * @param userId       用户主键
     * @param appServiceId 应用服务主键
     * @return 该应用的权限码集合
     */
    Set<String> computeAppPermissionCodes(Long realmId, Long userId, Long appServiceId);
}
