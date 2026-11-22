package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.AttributeDef;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户属性定义 Mapper(iam_attribute_def 单表)。
 * <p>属性键在域内唯一,删除为逻辑删除。</p>
 */
public interface AttributeDefMapper {

    /**
     * 按主键查询未删除属性定义。
     *
     * @param id 属性定义主键
     * @return 属性定义;不存在或已删除返回 null
     */
    AttributeDef selectById(@Param("id") Long id);

    /**
     * 按域与属性键查询未删除属性定义。
     *
     * @param realmId 身份域主键
     * @param attrKey 属性键
     * @return 属性定义;不存在返回 null
     */
    AttributeDef selectByRealmIdAndKey(@Param("realmId") Long realmId, @Param("attrKey") String attrKey);

    /**
     * 查询域内全部未删除属性定义。
     *
     * @param realmId 身份域主键
     * @return 属性定义列表;无数据时返回空列表
     */
    List<AttributeDef> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入属性定义,deleted 固定写入 FALSE。
     *
     * @param attributeDef 属性定义实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(AttributeDef attributeDef);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param attributeDef 属性定义实体,id 必填
     * @return 受影响行数
     */
    int updateById(AttributeDef attributeDef);

    /**
     * 逻辑删除属性定义。
     *
     * @param id 属性定义主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);
}
