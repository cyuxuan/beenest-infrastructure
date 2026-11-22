package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.SamlServiceProvider;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SAML 服务方登记 Mapper(iam_saml_service_provider)。
 * <p>全部查询排除已逻辑删除的行。</p>
 */
public interface SamlServiceProviderMapper {

    /**
     * 按域与 SP 实体 ID 查询登记。
     *
     * @param realmId  身份域主键
     * @param entityId SP 实体 ID
     * @return 登记;不存在返回 null
     */
    SamlServiceProvider selectByEntityId(@Param("realmId") Long realmId, @Param("entityId") String entityId);

    /**
     * 按域与站内别名查询登记。
     *
     * @param realmId 身份域主键
     * @param spKey   站内别名
     * @return 登记;不存在返回 null
     */
    SamlServiceProvider selectByKey(@Param("realmId") Long realmId, @Param("spKey") String spKey);

    /**
     * 按主键查询登记。
     *
     * @param id 登记主键
     * @return 登记;不存在返回 null
     */
    SamlServiceProvider selectById(@Param("id") Long id);

    /**
     * 列出域内全部登记。
     *
     * @param realmId 身份域主键
     * @return 登记列表;无记录返回空列表
     */
    List<SamlServiceProvider> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入登记。
     *
     * @param provider 登记实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(SamlServiceProvider provider);

    /**
     * 按主键更新全部可写字段。
     *
     * @param provider 登记实体,id 必填
     * @return 受影响行数
     */
    int updateById(SamlServiceProvider provider);

    /**
     * 按主键逻辑删除。
     *
     * @param realmId 身份域主键,用于限定删除范围
     * @param id      登记主键
     * @return 受影响行数
     */
    int softDelete(@Param("realmId") Long realmId, @Param("id") Long id);
}
