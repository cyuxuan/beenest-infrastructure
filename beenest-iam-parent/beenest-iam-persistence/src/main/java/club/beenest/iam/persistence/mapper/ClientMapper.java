package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.ClientReferenceCount;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 客户端 Mapper(iam_client 单表)。
 * <p>客户端按域隔离,查询均排除逻辑删除行。</p>
 */
public interface ClientMapper {

    /**
     * 按域与客户端标识查询未删除客户端。
     *
     * @param realmId   身份域主键
     * @param clientKey 客户端标识
     * @return 客户端;不存在返回 null
     */
    Client selectByRealmIdAndClientKey(@Param("realmId") Long realmId, @Param("clientKey") String clientKey);

    /**
     * 按主键查询未删除客户端。
     *
     * @param id 客户端主键
     * @return 客户端;不存在或已删除返回 null
     */
    Client selectById(@Param("id") Long id);

    /**
     * 查询域内全部未删除客户端,按主键升序。
     *
     * @param realmId 身份域主键
     * @return 客户端列表;无数据时返回空列表
     */
    List<Client> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 分页查询域内未删除客户端,按主键升序。
     * <p>过滤维度:客户端标识与名称模糊匹配,类型与启用状态精确匹配;条件为空时返回域内全部客户端。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 客户端列表;无匹配时返回空列表
     */
    List<Client> selectByCondition(@Param("realmId") Long realmId, @Param("client") Client condition);

    /**
     * 插入客户端,deleted 固定写入 FALSE。
     * <p>未赋值列交由 DDL 默认值填充,不写入显式 null。</p>
     *
     * @param client 客户端实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Client client);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>subjectMode 传空串表示清空并回落继承域策略,null 表示不修改。</p>
     *
     * @param client 客户端实体,id 必填
     * @return 受影响行数
     */
    int updateById(Client client);

    /**
     * 变更客户端类型,并同时重置密钥与 PKCE 要求。
     * <p>类型是客户端的协议身份(决定令牌端点认证方式),不参与通用更新;
     * 该语句显式写入三列,{@code clientSecret} 传 null 表示清空密钥(公共客户端)。</p>
     *
     * @param id           客户端主键
     * @param clientType   目标类型 PUBLIC / CONFIDENTIAL
     * @param clientSecret 密钥密文,公共客户端传 null
     * @param requirePkce  是否强制 PKCE
     * @return 受影响行数
     */
    int updateTypeById(@Param("id") Long id, @Param("clientType") String clientType,
                       @Param("clientSecret") String clientSecret,
                       @Param("requirePkce") Boolean requirePkce);

    /**
     * 逻辑删除客户端。
     *
     * @param id 客户端主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 统计域内未删除客户端数。
     *
     * @param realmId 身份域主键
     * @return 客户端数
     */
    long countByRealm(@Param("realmId") Long realmId);

    /**
     * 统计指向该客户端的下游引用,按类别给出明细。
     * <p>覆盖授权同意、客户端会话、客户端角色与绑定该客户端的应用服务——这些行归属其他主体,
     * 或代表正在生效的运行时状态,删除客户端前必须先解除。客户端自身的作用域分配
     * ({@code iam_client_scope_mapping})不在统计范围内:它是客户端的配置项,随客户端一起清理。</p>
     *
     * @param clientId 客户端主键
     * @return 分类引用计数;各项为 0 表示该类别无引用
     */
    ClientReferenceCount countReferences(@Param("clientId") Long clientId);
}
