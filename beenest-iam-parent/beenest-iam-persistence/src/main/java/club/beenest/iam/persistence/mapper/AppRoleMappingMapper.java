package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.AppRoleMapping;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用角色映射 Mapper(iam_app_role_mapping 单表)。
 * <p>映射把来源(用户、组或角色)指向应用角色,删除为逻辑删除。</p>
 */
public interface AppRoleMappingMapper {

    /**
     * 按主键查询未删除映射。
     *
     * @param id 映射主键
     * @return 映射;不存在或已删除返回 null
     */
    AppRoleMapping selectById(@Param("id") Long id);

    /**
     * 查询应用的全部未删除映射,含停用项。
     *
     * @param appServiceId 应用主键
     * @return 映射列表;无映射时返回空列表
     */
    List<AppRoleMapping> selectByAppServiceId(@Param("appServiceId") Long appServiceId);

    /**
     * 查询应用启用中的未删除映射,用于令牌角色计算。
     *
     * @param appServiceId 应用主键
     * @return 映射列表;无启用映射时返回空列表
     */
    List<AppRoleMapping> selectEnabledByAppServiceId(@Param("appServiceId") Long appServiceId);

    /**
     * 查询同一来源到同一目标角色编码的未删除映射,用于唯一性校验。
     *
     * @param appServiceId   应用主键
     * @param sourceType     来源类型(用户、组或角色)
     * @param sourceId       来源主键
     * @param targetRoleCode 目标角色编码
     * @return 映射;不存在返回 null
     */
    AppRoleMapping selectByAppAndSource(@Param("appServiceId") Long appServiceId,
                                        @Param("sourceType") String sourceType,
                                        @Param("sourceId") Long sourceId,
                                        @Param("targetRoleCode") String targetRoleCode);

    /**
     * 新增映射。
     *
     * @param mapping 映射实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(AppRoleMapping mapping);

    /**
     * 逻辑删除单条映射。
     *
     * @param id 映射主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 逻辑删除应用下的全部映射,应用删除时调用。
     *
     * @param appServiceId 应用主键
     * @return 受影响行数
     */
    int softDeleteByAppServiceId(@Param("appServiceId") Long appServiceId);
}
