package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.UserAttributeIndex;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户属性索引投影 Mapper(iam_user_attribute_index 单表)。
 * <p>把扩展属性投影为可检索的索引行;删除为逻辑删除。</p>
 */
public interface UserAttributeIndexMapper {

    /**
     * 查询用户的全部未删除索引行。
     *
     * @param userId 用户主键
     * @return 索引行列表;无属性时返回空列表
     */
    List<UserAttributeIndex> selectByUserId(@Param("userId") Long userId);

    /**
     * 按域、属性键与值查询未删除索引行,用于唯一性校验。
     *
     * @param realmId   身份域主键
     * @param attrKey   属性键
     * @param attrValue 属性值
     * @return 索引行;不存在返回 null
     */
    UserAttributeIndex selectByRealmKeyValue(@Param("realmId") Long realmId,
                                             @Param("attrKey") String attrKey,
                                             @Param("attrValue") String attrValue);

    /**
     * 按域、属性键与值检索未删除索引行。
     *
     * @param realmId   身份域主键
     * @param attrKey   属性键
     * @param attrValue 属性值
     * @return 索引行列表;无匹配时返回空列表
     */
    List<UserAttributeIndex> selectByRealmAndKey(@Param("realmId") Long realmId,
                                                 @Param("attrKey") String attrKey,
                                                 @Param("attrValue") String attrValue);

    /**
     * 插入索引行,deleted 固定写入 FALSE。
     *
     * @param userAttributeIndex 索引行实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(UserAttributeIndex userAttributeIndex);

    /**
     * 逻辑删除用户的某条属性索引行。
     *
     * @param userId  用户主键
     * @param attrKey 属性键
     * @return 受影响行数
     */
    int deleteByUserIdAndKey(@Param("userId") Long userId, @Param("attrKey") String attrKey);

    /**
     * 逻辑删除用户的全部属性索引行。
     *
     * @param userId 用户主键
     * @return 受影响行数
     */
    int deleteByUserId(@Param("userId") Long userId);
}
