package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.AppAdmin;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用管理员 Mapper(iam_app_admin 单表)。
 * <p>管理员按主体类型(用户或组)授权,删除为逻辑删除。</p>
 */
public interface AppAdminMapper {

    /**
     * 按主键查询未删除管理员关系。
     *
     * @param id 关系主键
     * @return 管理员关系;不存在或已删除返回 null
     */
    AppAdmin selectById(@Param("id") Long id);

    /**
     * 查询应用的全部未删除管理员关系。
     *
     * @param appServiceId 应用主键
     * @return 管理员关系列表;无管理员时返回空列表
     */
    List<AppAdmin> selectByAppServiceId(@Param("appServiceId") Long appServiceId);

    /**
     * 按域、主体类型与主体键查询未删除管理员关系,用于鉴权与管理范围计算。
     *
     * @param realmId     身份域主键
     * @param subjectType 主体类型(用户或组)
     * @param subjectIds  主体主键列表
     * @return 管理员关系列表;主体列表为空时返回空列表
     */
    List<AppAdmin> selectByRealmAndSubject(@Param("realmId") Long realmId,
                                           @Param("subjectType") String subjectType,
                                           @Param("subjectIds") List<Long> subjectIds);

    /**
     * 查询应用下某一主体的未删除管理员关系,用于唯一性校验。
     *
     * @param appServiceId 应用主键
     * @param subjectType  主体类型(用户或组)
     * @param subjectId    主体主键
     * @return 管理员关系;不存在返回 null
     */
    AppAdmin selectByAppAndSubject(@Param("appServiceId") Long appServiceId,
                                   @Param("subjectType") String subjectType,
                                   @Param("subjectId") Long subjectId);

    /**
     * 新增管理员关系。
     *
     * @param appAdmin 管理员关系实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(AppAdmin appAdmin);

    /**
     * 逻辑删除单条管理员关系。
     *
     * @param id 关系主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 逻辑删除应用下的全部管理员关系,应用删除时调用。
     *
     * @param appServiceId 应用主键
     * @return 受影响行数
     */
    int softDeleteByAppServiceId(@Param("appServiceId") Long appServiceId);
}
