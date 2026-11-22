package club.beenest.iam.persistence.mapper;

import club.beenest.iam.persistence.entity.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用户 Mapper(iam_user 单表)。
 * <p>用户按身份域隔离,查询与更新均排除逻辑删除行。</p>
 */
public interface UserMapper {

    /**
     * 按域与用户名查询未删除用户。
     *
     * @param realmId  身份域主键
     * @param username 用户名,精确匹配
     * @return 用户;不存在返回 null
     */
    User selectByRealmIdAndUsername(@Param("realmId") Long realmId, @Param("username") String username);

    /**
     * 按域与已验证手机号查询未删除用户,用于短信验证码登录定位账号。
     *
     * @param realmId 身份域主键
     * @param phone   手机号,精确匹配
     * @return 用户;手机号未验证或不存在返回 null
     */
    User selectByRealmIdAndPhone(@Param("realmId") Long realmId, @Param("phone") String phone);

    /**
     * 按主键查询未删除用户。
     *
     * @param id 用户主键
     * @return 用户;不存在或已删除返回 null
     */
    User selectById(@Param("id") Long id);

    /**
     * 按主键批量查询未删除用户。
     *
     * @param ids 用户主键列表,不能为空
     * @return 用户列表;顺序不保证
     */
    List<User> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 按域分页查询未删除用户,按主键升序。
     * <p>过滤维度:用户名/昵称/邮箱模糊匹配,状态与用户类型精确匹配;条件为空时返回域内全部用户。</p>
     *
     * @param realmId   身份域主键
     * @param condition 过滤条件,可为 null
     * @return 用户列表;无匹配时返回空列表
     */
    List<User> selectByCondition(@Param("realmId") Long realmId, @Param("user") User condition);

    /**
     * 插入用户,deleted 固定写入 FALSE。
     *
     * @param user 用户实体
     * @return 受影响行数;主键回填至 id
     */
    int insert(User user);

    /**
     * 按主键更新非空字段并刷新更新时间。
     * <p>用户名、用户类型与令牌版本不在此更新。</p>
     *
     * @param user 用户实体,id 必填
     * @return 受影响行数;用户不存在或已删除时为 0
     */
    int updateById(User user);

    /**
     * 批量更新用户状态,仅作用于未删除用户。
     * <p>入参须属于同一身份域,跨域校验由调用方完成。</p>
     *
     * @param ids    用户主键列表,不能为空
     * @param status 目标状态
     * @return 受影响行数
     */
    int updateStatusByIds(@Param("ids") List<Long> ids, @Param("status") Integer status);

    /**
     * 按状态分组统计域内未删除用户数。
     *
     * @param realmId 身份域主键
     * @return 每行含 status 与 total 两个键;无用户时返回空列表
     */
    List<Map<String, Object>> countByStatusGroup(@Param("realmId") Long realmId);

    /**
     * 令牌版本递增 1,使该用户已签发的令牌全部失效。
     *
     * @param id 用户主键
     * @return 受影响行数;用户不存在或已删除时为 0
     */
    int incrementTokenVersion(@Param("id") Long id);

    /**
     * 覆盖写入登录后的待办事项。
     *
     * @param id              用户主键
     * @param requiredActions 逗号分隔的待办编码;空串表示无待办
     * @return 受影响行数
     */
    int updateRequiredActions(@Param("id") Long id, @Param("requiredActions") String requiredActions);

    /**
     * 置邮箱已验证标记。
     *
     * @param id 用户主键
     * @return 受影响行数
     */
    int markEmailVerified(@Param("id") Long id);

    /**
     * 按域与邮箱查询未删除用户,邮箱忽略大小写。
     * <p>多条匹配时取主键最小的一条。</p>
     *
     * @param realmId 身份域主键
     * @param email   邮箱
     * @return 用户;不存在返回 null
     */
    User selectByRealmIdAndEmail(@Param("realmId") Long realmId, @Param("email") String email);

    /**
     * 逻辑删除用户。
     *
     * @param id 用户主键
     * @return 受影响行数;用户不存在或已删除时为 0
     */
    int softDeleteById(@Param("id") Long id);
}
