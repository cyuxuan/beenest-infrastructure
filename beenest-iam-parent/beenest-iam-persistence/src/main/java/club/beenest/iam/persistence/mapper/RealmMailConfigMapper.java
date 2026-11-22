package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.RealmMailConfig;
import org.apache.ibatis.annotations.Param;

/**
 * 域级发信配置 Mapper(iam_realm_mail_config,每域一行)。
 * <p>口令以密文存储,查询排除已逻辑删除配置。</p>
 */
public interface RealmMailConfigMapper {

    /**
     * 按域查询未删除的发信配置。
     *
     * @param realmId 身份域主键
     * @return 配置;未配置返回 null
     */
    RealmMailConfig selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入配置,deleted 固定写入 FALSE。
     *
     * @param config 配置实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(RealmMailConfig config);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param config 配置实体,id 必填
     * @return 受影响行数;password 为空串时保持原值
     */
    int updateById(RealmMailConfig config);
}
