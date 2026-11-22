package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.Realm;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 身份域 Mapper(iam_realm 单表)。
 * <p>realmKey 全局唯一,同一实例仅允许一个默认域;查询排除逻辑删除行。</p>
 */
public interface RealmMapper {

    /**
     * 按域标识查询未删除域。
     *
     * @param realmKey 域标识
     * @return 身份域;不存在返回 null
     */
    Realm selectByRealmKey(@Param("realmKey") String realmKey);

    /**
     * 按主键查询未删除域。
     *
     * @param id 身份域主键
     * @return 身份域;不存在或已删除返回 null
     */
    Realm selectById(@Param("id") Long id);

    /**
     * 查询全部启用且未删除的域,按主键升序。
     *
     * @return 身份域列表;无启用域时返回空列表
     */
    List<Realm> selectEnabled();

    /**
     * 分页查询未删除域,按主键升序。
     * <p>过滤维度:域标识精确匹配,域名模糊匹配;条件为空时返回全部域。</p>
     *
     * @param condition 过滤条件,可为 null
     * @return 身份域列表;无匹配时返回空列表
     */
    List<Realm> selectByCondition(@Param("realm") Realm condition);

    /**
     * 插入域,deleted 固定写入 FALSE。
     *
     * @param realm 身份域实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(Realm realm);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>域标识不可修改;theme 传空串表示清空并回落跟随全局。</p>
     *
     * @param realm 身份域实体,id 必填
     * @return 受影响行数
     */
    int updateById(Realm realm);

    /**
     * 逻辑删除域。
     *
     * @param id 身份域主键
     * @return 受影响行数
     */
    int softDeleteById(@Param("id") Long id);

    /**
     * 查询默认域,多条时取主键最小的一条。
     *
     * @return 默认域;未设置时返回 null
     */
    Realm selectDefaultRealm();
}
