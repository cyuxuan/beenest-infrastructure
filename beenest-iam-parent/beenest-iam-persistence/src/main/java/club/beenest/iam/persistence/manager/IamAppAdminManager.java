package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.AppAdmin;
import club.beenest.iam.persistence.mapper.AppAdminMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用管理员存储(iam_app_admin)。
 * <p>管理员按用户或用户组登记;组管理员在该组成员登录时参与鉴权。</p>
 */
@Component
public class IamAppAdminManager {

    private static final String SUBJECT_USER = "USER";
    private static final String SUBJECT_GROUP = "GROUP";

    private final AppAdminMapper appAdminMapper;

    public IamAppAdminManager(AppAdminMapper appAdminMapper) {
        this.appAdminMapper = appAdminMapper;
    }

    /**
     * 应用的管理员名单。
     *
     * @param appServiceId 应用服务主键
     * @return 管理员关系列表
     */
    public List<AppAdmin> listByAppServiceId(Long appServiceId) {
        return appAdminMapper.selectByAppServiceId(appServiceId);
    }

    /**
     * 按主键查询。
     *
     * @param id 管理员关系主键
     * @return 管理员关系;不存在返回 null
     */
    public AppAdmin getById(Long id) {
        return appAdminMapper.selectById(id);
    }

    /**
     * 应用下某一主体的管理员关系(唯一性校验)。
     *
     * @param appServiceId 应用服务主键
     * @param subjectType 主体类型(USER / GROUP)
     * @param subjectId 主体主键
     * @return 管理员关系;不存在返回 null
     */
    public AppAdmin getByAppAndSubject(Long appServiceId, String subjectType, Long subjectId) {
        return appAdminMapper.selectByAppAndSubject(appServiceId, subjectType, subjectId);
    }

    /**
     * 新增管理员关系。
     *
     * @param appAdmin 管理员关系实体
     * @return 受影响行数
     */
    public int create(AppAdmin appAdmin) {
        return appAdminMapper.insert(appAdmin);
    }

    /**
     * 移除单条管理员关系。
     *
     * @param id 管理员关系主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return appAdminMapper.softDeleteById(id);
    }

    /**
     * 应用删除时清理管理员关系。
     *
     * @param appServiceId 应用服务主键
     * @return 受影响行数
     */
    public int removeByAppServiceId(Long appServiceId) {
        return appAdminMapper.softDeleteByAppServiceId(appServiceId);
    }

    /**
     * 用户是否可管理指定应用。
     * <p>用户直授与组继承两条路径取并集。</p>
     *
     * @param realmId 身份域主键
     * @param appServiceId 应用服务主键
     * @param userId 用户主键
     * @param groupIds 用户所属组主键
     * @return true 表示该用户是应用管理员
     */
    public boolean isAdmin(Long realmId, Long appServiceId, Long userId, List<Long> groupIds) {
        for (AppAdmin admin : appAdminMapper.selectByAppServiceId(appServiceId)) {
            if (!realmId.equals(admin.getRealmId())) {
                continue;
            }
            if (SUBJECT_USER.equals(admin.getSubjectType()) && userId.equals(admin.getSubjectId())) {
                return true;
            }
            if (SUBJECT_GROUP.equals(admin.getSubjectType())
                    && groupIds != null && groupIds.contains(admin.getSubjectId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用户可管理的应用主键集合(按用户直授与组继承)。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @param groupIds 用户所属组主键
     * @return 应用服务主键集合
     */
    public Set<Long> listManagedAppIds(Long realmId, Long userId, List<Long> groupIds) {
        Set<Long> appIds = new HashSet<>();
        for (AppAdmin admin : appAdminMapper.selectByRealmAndSubject(
                realmId, SUBJECT_USER, List.of(userId))) {
            appIds.add(admin.getAppServiceId());
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            for (AppAdmin admin : appAdminMapper.selectByRealmAndSubject(
                    realmId, SUBJECT_GROUP, groupIds)) {
                appIds.add(admin.getAppServiceId());
            }
        }
        return appIds;
    }
}
