package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.PasswordPolicy;
import org.apache.ibatis.annotations.Param;

/**
 * 口令策略 Mapper(iam_password_policy,每域一行)。
 * <p>查询排除已逻辑删除策略。</p>
 */
public interface PasswordPolicyMapper {

    /**
     * 按域查询未删除的口令策略。
     *
     * @param realmId 身份域主键
     * @return 策略;未配置返回 null
     */
    PasswordPolicy selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入策略,deleted 固定写入 FALSE。
     *
     * @param passwordPolicy 策略实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(PasswordPolicy passwordPolicy);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param passwordPolicy 策略实体,id 必填
     * @return 受影响行数
     */
    int updateById(PasswordPolicy passwordPolicy);
}
