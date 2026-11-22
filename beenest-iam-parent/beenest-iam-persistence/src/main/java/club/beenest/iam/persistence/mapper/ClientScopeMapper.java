package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.ClientScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 客户端作用域 Mapper(iam_client_scope 单表)。
 * <p>作用域键在域内唯一,删除为逻辑删除。</p>
 */
public interface ClientScopeMapper {

    /**
     * 按主键查询未删除作用域。
     *
     * @param id 作用域主键
     * @return 作用域;不存在或已删除返回 null
     */
    ClientScope selectById(@Param("id") Long id);

    /**
     * 按域与作用域键查询未删除作用域。
     *
     * @param realmId  身份域主键
     * @param scopeKey 作用域键
     * @return 作用域;不存在返回 null
     */
    ClientScope selectByRealmIdAndKey(@Param("realmId") Long realmId, @Param("scopeKey") String scopeKey);

    /**
     * 查询域内全部未删除作用域。
     *
     * @param realmId 身份域主键
     * @return 作用域列表;无数据时返回空列表
     */
    List<ClientScope> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入作用域,deleted 固定写入 FALSE。
     *
     * @param clientScope 作用域实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(ClientScope clientScope);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param clientScope 作用域实体,id 必填
     * @return 受影响行数
     */
    int updateById(ClientScope clientScope);

    /**
     * 逻辑删除作用域。
     *
     * @param id 作用域主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
