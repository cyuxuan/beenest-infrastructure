package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.ProtocolMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 协议映射器 Mapper(iam_protocol_mapper 单表)。
 * <p>client_id 为空表示域级映射,对域内全部客户端生效;查询排除逻辑删除行。</p>
 */
public interface ProtocolMapperMapper {

    /**
     * 按主键查询未删除映射器。
     *
     * @param id 映射器主键
     * @return 映射器;不存在或已删除返回 null
     */
    ProtocolMapper selectById(@Param("id") Long id);

    /**
     * 查询域级映射器,即 client_id 为空且未删除的记录,按主键升序。
     *
     * @param realmId 身份域主键
     * @return 映射器列表;无配置时返回空列表
     */
    List<ProtocolMapper> selectRealmLevel(@Param("realmId") Long realmId);

    /**
     * 查询某客户端生效的映射器,含域级与客户端级,按主键升序。
     *
     * @param realmId  身份域主键
     * @param clientId 客户端主键
     * @return 映射器列表;无配置时返回空列表
     */
    List<ProtocolMapper> selectEffectiveByClientId(@Param("realmId") Long realmId,
                                                   @Param("clientId") Long clientId);

    /**
     * 插入映射器,deleted 固定写入 FALSE。
     *
     * @param protocolMapper 映射器实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(ProtocolMapper protocolMapper);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param protocolMapper 映射器实体,id 必填
     * @return 受影响行数
     */
    int updateById(ProtocolMapper protocolMapper);

    /**
     * 逻辑删除映射器。
     *
     * @param id 映射器主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
