package club.beenest.iam.core.spi.provider;

import club.beenest.iam.core.model.RoleModel;

import java.util.List;
import java.util.Optional;

/**
 * 角色存储 SPI。
 * <p>由持久层实现;登录时为用户加载角色(映射为 GrantedAuthority 与令牌角色声明)。</p>
 */
public interface RoleProvider {

    /**
     * 加载用户在某身份域下的全部角色。
     *
     * @param realmId 所属身份域
     * @param userId  用户主键
     * @return 角色列表(REALM 与 CLIENT 两类容器);无角色返回空列表
     */
    List<RoleModel> findRolesByUserId(Long realmId, Long userId);

    /**
     * 按域与角色编码查找域级角色。
     * <p>外部身份按角色自动授权时使用。</p>
     *
     * @param realmId  所属身份域
     * @param roleCode 角色编码
     * @return 域级角色;不存在时为空
     */
    Optional<RoleModel> findByCode(Long realmId, String roleCode);

    /**
     * 追加授予角色。
     * <p>幂等:已授予的忽略,不影响用户已有的其他角色。授权后该域权限缓存立即失效。</p>
     *
     * @param realmId 所属身份域
     * @param userId  用户主键
     * @param roleIds 待授予的角色主键
     */
    void grantRoles(Long realmId, Long userId, List<Long> roleIds);
}
