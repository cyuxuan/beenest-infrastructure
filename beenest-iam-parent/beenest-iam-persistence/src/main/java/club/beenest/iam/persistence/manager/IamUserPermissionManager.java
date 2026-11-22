package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.UserPermission;
import club.beenest.iam.persistence.mapper.UserPermissionMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户级权限特批存储(iam_user_permission)。
 * <p>同一用户对同一权限只有一条有效记录:GRANT 与 REVOKE 互斥,
 * 重新设置时覆盖原记录,历史通过审计日志追溯。</p>
 */
@Component
public class IamUserPermissionManager {

    private final UserPermissionMapper userPermissionMapper;
    /** 权限缓存代次:用户特批变更后自增 */
    private final IamPermissionEpoch permissionEpoch;

    public IamUserPermissionManager(UserPermissionMapper userPermissionMapper,
                                    IamPermissionEpoch permissionEpoch) {
        this.userPermissionMapper = userPermissionMapper;
        this.permissionEpoch = permissionEpoch;
    }

    /**
     * 查询用户的全部特批记录。
     *
     * @param userId 用户主键
     * @return 特批记录,含已过期的行
     */
    public List<UserPermission> listByUserId(Long userId) {
        return userPermissionMapper.selectByUserId(userId);
    }

    /**
     * 查询用户对单个权限的有效特批记录。
     *
     * @param userId 用户主键
     * @param permissionId 权限主键
     * @return 特批记录;不存在返回 null
     */
    public UserPermission getByUserAndPermission(Long userId, Long permissionId) {
        return userPermissionMapper.selectByUserAndPermission(userId, permissionId);
    }

    /**
     * 写入或覆盖特批关系,写库完成后失效该域权限缓存。
     *
     * @param userPermission 特批记录,需填 realmId / userId / permissionId / grantType
     * @return 落库后的特批记录
     */
    public UserPermission upsert(UserPermission userPermission) {
        userPermissionMapper.upsert(userPermission);
        // 事务内提交后自增代次
        permissionEpoch.bump(userPermission.getRealmId());
        return userPermissionMapper.selectByUserAndPermission(
                userPermission.getUserId(), userPermission.getPermissionId());
    }

    /**
     * 逻辑删除特批关系,并失效该域权限缓存。
     *
     * @param id 特批记录主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        // 删除前先取域标识,代次按域自增
        UserPermission existing = userPermissionMapper.selectById(id);
        int rows = userPermissionMapper.softDeleteById(id);
        if (existing != null) {
            permissionEpoch.bump(existing.getRealmId());
        }
        return rows;
    }
}
