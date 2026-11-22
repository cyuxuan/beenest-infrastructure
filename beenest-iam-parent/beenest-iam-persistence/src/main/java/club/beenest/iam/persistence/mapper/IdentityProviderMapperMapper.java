package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.IdentityProviderMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 身份提供方属性映射器 Mapper(iam_identity_provider_mapper 单表)。
 * <p>映射器定义外部声明到本地用户属性的转换,删除为逻辑删除。</p>
 */
public interface IdentityProviderMapperMapper {

    /**
     * 按主键查询未删除映射器。
     *
     * @param id 映射器主键
     * @return 映射器;不存在或已删除返回 null
     */
    IdentityProviderMapper selectById(@Param("id") Long id);

    /**
     * 查询提供方的全部未删除映射器。
     *
     * @param idpId 提供方主键
     * @return 映射器列表;无配置时返回空列表
     */
    List<IdentityProviderMapper> selectByIdpId(@Param("idpId") Long idpId);

    /**
     * 插入映射器,deleted 固定写入 FALSE。
     *
     * @param identityProviderMapper 映射器实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(IdentityProviderMapper identityProviderMapper);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param identityProviderMapper 映射器实体,id 必填
     * @return 受影响行数
     */
    int updateById(IdentityProviderMapper identityProviderMapper);

    /**
     * 逻辑删除映射器。
     *
     * @param id 映射器主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 逻辑删除提供方下的全部映射器。
     *
     * @param idpId 提供方主键
     * @return 受影响行数
     */
    int softDeleteByIdpId(@Param("idpId") Long idpId);
}
