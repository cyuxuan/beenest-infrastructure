package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.IdentityProvider;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 身份提供方 Mapper(iam_identity_provider 单表)。
 * <p>提供方按域隔离,别名在域内唯一;删除为逻辑删除。</p>
 */
public interface IdentityProviderMapper {

    /**
     * 按主键查询未删除提供方。
     *
     * @param id 提供方主键
     * @return 提供方;不存在或已删除返回 null
     */
    IdentityProvider selectById(@Param("id") Long id);

    /**
     * 按域与别名查询未删除提供方。
     *
     * @param realmId 身份域主键
     * @param alias   提供方别名
     * @return 提供方;不存在返回 null
     */
    IdentityProvider selectByRealmIdAndAlias(@Param("realmId") Long realmId, @Param("alias") String alias);

    /**
     * 查询域内全部未删除提供方。
     *
     * @param realmId 身份域主键
     * @return 提供方列表;无数据时返回空列表
     */
    List<IdentityProvider> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入提供方,deleted 固定写入 FALSE。
     *
     * @param identityProvider 提供方实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(IdentityProvider identityProvider);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param identityProvider 提供方实体,id 必填
     * @return 受影响行数
     */
    int updateById(IdentityProvider identityProvider);

    /**
     * 逻辑删除提供方。
     *
     * @param id 提供方主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
