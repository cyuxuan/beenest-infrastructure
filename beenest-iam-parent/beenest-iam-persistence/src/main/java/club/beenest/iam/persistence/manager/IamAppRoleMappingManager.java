package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.AppRoleMapping;
import club.beenest.iam.persistence.mapper.AppRoleMappingMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 应用角色映射存储(iam_app_role_mapping)。
 * <p>MAPPED 应用以映射结果作为令牌 {@code app_roles},不向应用暴露 IAM 内部角色码。</p>
 */
@Component
public class IamAppRoleMappingManager {

    private static final String SOURCE_GROUP = "GROUP";
    private static final String SOURCE_REALM_ROLE = "REALM_ROLE";

    private final AppRoleMappingMapper mappingMapper;

    public IamAppRoleMappingManager(AppRoleMappingMapper mappingMapper) {
        this.mappingMapper = mappingMapper;
    }

    /**
     * 应用的全部映射(含停用项,管理端展示)。
     *
     * @param appServiceId 应用服务主键
     * @return 映射列表
     */
    public List<AppRoleMapping> listByAppServiceId(Long appServiceId) {
        return mappingMapper.selectByAppServiceId(appServiceId);
    }

    /**
     * 按主键查询。
     *
     * @param id 映射主键
     * @return 映射;不存在返回 null
     */
    public AppRoleMapping getById(Long id) {
        return mappingMapper.selectById(id);
    }

    /**
     * 同一来源到同一目标角色的映射(唯一性校验)。
     *
     * @param appServiceId 应用服务主键
     * @param sourceType 来源类型(GROUP / REALM_ROLE)
     * @param sourceId 来源主键(组主键或角色主键)
     * @param targetRoleCode 目标角色编码
     * @return 映射;不存在返回 null
     */
    public AppRoleMapping getByAppAndSource(Long appServiceId, String sourceType, Long sourceId,
                                            String targetRoleCode) {
        return mappingMapper.selectByAppAndSource(appServiceId, sourceType, sourceId, targetRoleCode);
    }

    /**
     * 新增映射。
     *
     * @param mapping 映射实体
     * @return 受影响行数
     */
    public int create(AppRoleMapping mapping) {
        return mappingMapper.insert(mapping);
    }

    /**
     * 移除单条映射。
     *
     * @param id 映射主键
     * @return 受影响行数
     */
    public int remove(Long id) {
        return mappingMapper.softDeleteById(id);
    }

    /**
     * 应用删除时清理映射。
     *
     * @param appServiceId 应用服务主键
     * @return 受影响行数
     */
    public int removeByAppServiceId(Long appServiceId) {
        return mappingMapper.softDeleteByAppServiceId(appServiceId);
    }

    /**
     * 解析用户命中的应用角色码。
     * <p>只读取启用中的映射;来源为用户所属组或用户有效角色之一即命中;
     * 结果去重并排序。</p>
     *
     * @param realmId 身份域主键
     * @param appServiceId 应用服务主键
     * @param groupIds 用户所属组主键
     * @param roleIds 用户有效角色主键
     * @return 命中的目标角色码,去重并排序
     */
    public List<String> resolveTargetRoleCodes(Long realmId, Long appServiceId,
                                               List<Long> groupIds, List<Long> roleIds) {
        return mappingMapper.selectEnabledByAppServiceId(appServiceId).stream()
                .filter(mapping -> realmId.equals(mapping.getRealmId()))
                .filter(mapping -> matches(mapping, groupIds, roleIds))
                .map(AppRoleMapping::getTargetRoleCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 映射来源是否命中用户的有效组或有效角色。
     *
     * @param mapping 映射
     * @param groupIds 用户所属组主键
     * @param roleIds 用户有效角色主键
     * @return true 表示命中
     */
    private static boolean matches(AppRoleMapping mapping, List<Long> groupIds, List<Long> roleIds) {
        if (SOURCE_GROUP.equals(mapping.getSourceType())) {
            return groupIds != null && groupIds.contains(mapping.getSourceId());
        }
        if (SOURCE_REALM_ROLE.equals(mapping.getSourceType())) {
            return roleIds != null && roleIds.contains(mapping.getSourceId());
        }
        return false;
    }
}
