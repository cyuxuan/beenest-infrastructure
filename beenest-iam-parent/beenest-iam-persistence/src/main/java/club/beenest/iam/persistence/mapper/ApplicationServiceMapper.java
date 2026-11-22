package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.ApplicationService;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 应用服务 Mapper(iam_application_service 单表)。
 * <p>应用按域隔离,查询均排除逻辑删除行。</p>
 */
public interface ApplicationServiceMapper {

    /**
     * 按主键集合批量查询未删除应用。
     *
     * @param ids 应用主键集合
     * @return 应用列表;集合为空时返回空列表
     */
    List<ApplicationService> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 查询域内全部未删除应用,按排序号与主键升序。
     *
     * @param realmId 身份域主键
     * @return 应用列表;无数据时返回空列表
     */
    List<ApplicationService> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 按域与应用编码查询未删除应用。
     *
     * @param realmId 身份域主键
     * @param appCode 应用编码
     * @return 应用;不存在返回 null
     */
    ApplicationService selectByRealmIdAndAppCode(@Param("realmId") Long realmId, @Param("appCode") String appCode);

    /**
     * 按主键查询未删除应用。
     *
     * @param id 应用主键
     * @return 应用;不存在或已删除返回 null
     */
    ApplicationService selectById(@Param("id") Long id);

    /**
     * 按绑定的客户端查询未删除应用,用于令牌按应用裁剪与唯一绑定校验。
     *
     * @param realmId     身份域主键
     * @param ssoClientPk 客户端主键
     * @return 应用;未绑定返回 null
     */
    ApplicationService selectBySsoClientPk(@Param("realmId") Long realmId,
                                           @Param("ssoClientPk") Long ssoClientPk);

    /**
     * 分页查询域内未删除应用,按排序号与主键升序。
     * <p>过滤维度:应用编码与名称模糊匹配,类型、分类、可见性、授权模式与启用状态精确匹配;
     * 条件为空时返回域内全部应用。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 应用列表;无匹配时返回空列表
     */
    List<ApplicationService> selectByCondition(@Param("realmId") Long realmId,
                                               @Param("app") ApplicationService condition);

    /**
     * 插入应用,deleted 固定写入 FALSE。
     *
     * @param applicationService 应用实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(ApplicationService applicationService);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>应用编码不可修改。</p>
     *
     * @param applicationService 应用实体,id 必填
     * @return 受影响行数
     */
    int updateById(ApplicationService applicationService);

    /**
     * 逻辑删除应用。
     *
     * @param id 应用主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
