package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.LoginPolicy;
import org.apache.ibatis.annotations.Param;

/**
 * 登录策略 Mapper(iam_login_policy,每域一行)。
 * <p>查询排除已逻辑删除策略。</p>
 */
public interface LoginPolicyMapper {

    /**
     * 按域查询未删除的登录策略。
     *
     * @param realmId 身份域主键
     * @return 策略;未配置返回 null
     */
    LoginPolicy selectByRealmId(@Param("realmId") Long realmId);

    /**
     * 插入策略,deleted 固定写入 FALSE。
     *
     * @param loginPolicy 策略实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(LoginPolicy loginPolicy);

    /**
     * 按主键更新非空字段并刷新更新时间。
     *
     * @param loginPolicy 策略实体,id 必填
     * @return 受影响行数
     */
    int updateById(LoginPolicy loginPolicy);
}
