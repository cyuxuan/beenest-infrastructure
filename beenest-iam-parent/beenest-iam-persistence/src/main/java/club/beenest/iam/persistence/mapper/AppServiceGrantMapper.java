package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.AppServiceGrant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用服务授权 Mapper(iam_app_service_grant 单表)。
 * <p>授权按主体类型批量登记,删除为逻辑删除。</p>
 */
public interface AppServiceGrantMapper {

    /**
     * 查询应用的全部未删除授权主体。
     *
     * @param appServiceId 应用主键
     * @return 授权记录列表;无授权时返回空列表
     */
    List<AppServiceGrant> selectByAppServiceId(@Param("appServiceId") Long appServiceId);

    /**
     * 按主体类型与主体键查询未删除授权记录,用于启动台过滤。
     *
     * @param subjectType 主体类型(用户或角色)
     * @param subjectIds  主体主键列表
     * @return 授权记录列表;主体列表为空时返回空列表
     */
    List<AppServiceGrant> selectBySubject(@Param("subjectType") String subjectType, @Param("subjectIds") List<Long> subjectIds);

    /**
     * 批量写入授权记录,每个主体一行。
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用主键
     * @param subjectType  主体类型
     * @param subjectIds   主体主键列表,不能为空
     * @return 受影响行数
     */
    int insertBatch(@Param("realmId") Long realmId,
                    @Param("appServiceId") Long appServiceId,
                    @Param("subjectType") String subjectType,
                    @Param("subjectIds") List<Long> subjectIds);

    /**
     * 逻辑删除应用在某一主体类型下的全部授权,整体覆盖写入前调用。
     *
     * @param appServiceId 应用主键
     * @param subjectType  主体类型
     * @return 受影响行数
     */
    int softDeleteByAppServiceId(@Param("appServiceId") Long appServiceId,
                                 @Param("subjectType") String subjectType);

    /**
     * 逻辑删除某个主体的全部应用授权,主体被删除时调用。
     * <p>残留授权行会被管理端回读并原样提交,整表覆盖校验按"主体不存在"拒绝保存。</p>
     *
     * @param subjectType 主体类型
     * @param subjectId   主体主键
     * @return 受影响行数
     */
    int softDeleteBySubject(@Param("subjectType") String subjectType,
                            @Param("subjectId") Long subjectId);
}
