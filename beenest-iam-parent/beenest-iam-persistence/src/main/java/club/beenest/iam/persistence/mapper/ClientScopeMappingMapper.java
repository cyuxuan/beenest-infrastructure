package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.ClientScopeMapping;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 客户端-作用域关联 Mapper(iam_client_scope_mapping 单表)。
 * <p>关联删除为逻辑删除,查询排除已删除关联。</p>
 */
public interface ClientScopeMappingMapper {

    /**
     * 查询客户端已分配的未删除作用域关联。
     *
     * @param clientId 客户端主键
     * @return 关联列表;未分配作用域时返回空列表
     */
    List<ClientScopeMapping> selectByClientId(@Param("clientId") Long clientId);

    /**
     * 查询作用域被分配的未删除客户端关联。
     *
     * @param scopeId 作用域主键
     * @return 关联列表;未分配客户端时返回空列表
     */
    List<ClientScopeMapping> selectByScopeId(@Param("scopeId") Long scopeId);

    /**
     * 插入关联,deleted 固定写入 FALSE。
     *
     * @param clientScopeMapping 关联实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(ClientScopeMapping clientScopeMapping);

    /**
     * 逻辑删除客户端的某条作用域关联。
     *
     * @param clientId 客户端主键
     * @param scopeId  作用域主键
     * @return 受影响行数
     */
    int deleteByClientIdAndScopeId(@Param("clientId") Long clientId, @Param("scopeId") Long scopeId);

    /**
     * 逻辑删除客户端的全部作用域关联。
     *
     * @param clientId 客户端主键
     * @return 受影响行数
     */
    int deleteByClientId(@Param("clientId") Long clientId);
}
