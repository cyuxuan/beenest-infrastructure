package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Position;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 岗位 Mapper(iam_position 单表)。
 * <p>岗位编码在域内唯一,查询均排除逻辑删除行。</p>
 */
public interface PositionMapper {

    /**
     * 按主键查询未删除岗位。
     *
     * @param id 岗位主键
     * @return 岗位;不存在或已删除返回 null
     */
    Position selectById(@Param("id") Long id);

    /**
     * 按域与岗位编码查询未删除岗位,用于编码唯一性校验。
     *
     * @param realmId      身份域主键
     * @param positionCode 岗位编码
     * @return 岗位;不存在返回 null
     */
    Position selectByRealmIdAndCode(@Param("realmId") Long realmId, @Param("positionCode") String positionCode);

    /**
     * 查询域内全部未删除岗位,按排序号与主键升序。
     *
     * @param realmId 身份域主键
     * @return 岗位列表;无数据时返回空列表
     */
    List<Position> selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 分页查询域内未删除岗位,按排序号与主键升序。
     * <p>过滤维度:编码与名称模糊匹配;条件为空时返回域内全部岗位。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 岗位列表;无匹配时返回空列表
     */
    List<Position> selectByCondition(@Param("realmId") Long realmId, @Param("position") Position condition);

    /**
     * 按主键批量查询未删除岗位,用于任职信息回显。
     *
     * @param ids 岗位主键列表,不能为空
     * @return 岗位列表;顺序不保证
     */
    List<Position> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 插入岗位,deleted 固定写入 FALSE。
     *
     * @param position 岗位实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Position position);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>岗位编码不可修改。</p>
     *
     * @param position 岗位实体,id 必填
     * @return 受影响行数
     */
    int updateById(Position position);

    /**
     * 逻辑删除岗位。
     *
     * @param id 岗位主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 统计域内未删除岗位数。
     *
     * @param realmId 身份域主键
     * @return 岗位数
     */
    long countByRealm(@Param("realmId") Long realmId);
}
