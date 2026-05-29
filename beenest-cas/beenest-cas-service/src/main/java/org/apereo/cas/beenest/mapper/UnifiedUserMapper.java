package org.apereo.cas.beenest.mapper;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一用户 Mapper
 * <p>
 * 复用旧实现中的用户 CRUD，适配 Apereo CAS 架构。
 */
@Mapper
public interface UnifiedUserMapper {

    UnifiedUserDO selectByUserId(@Param("userId") String userId);

    UnifiedUserDO selectByUsername(@Param("username") String username);

    UnifiedUserDO selectByPhone(@Param("phone") String phone);

    UnifiedUserDO selectByEmail(@Param("email") String email);

    /** 根据微信 openid + loginType 查找 */
    UnifiedUserDO selectByOpenid(@Param("openid") String openid, @Param("loginType") String loginType);

    /** 根据微信 unionid 查找 */
    UnifiedUserDO selectByUnionid(@Param("unionid") String unionid);

    /** 根据抖音 openid 查找 */
    UnifiedUserDO selectByDouyinOpenid(@Param("douyinOpenid") String douyinOpenid);

    /** 根据抖音 unionid 查找 */
    UnifiedUserDO selectByDouyinUnionid(@Param("douyinUnionid") String douyinUnionid);

    /** 根据支付宝 UID 查找 */
    UnifiedUserDO selectByAlipayUid(@Param("alipayUid") String alipayUid);

    /** 批量查询用户，避免 N+1 问题 */
    List<UnifiedUserDO> selectByUserIds(@Param("userIds") List<String> userIds);

    void insert(UnifiedUserDO user);

    void updateByUserId(UnifiedUserDO user);

    /** 更新登录信息（时间、IP、UA、设备） */
    void updateLoginInfo(@Param("userId") String userId,
                         @Param("loginIp") String loginIp,
                         @Param("loginUa") String loginUa,
                         @Param("loginDevice") String loginDevice,
                         @Param("loginType") String loginType);

    /** 更新用户状态 */
    void updateStatus(@Param("userId") String userId, @Param("status") Integer status);

    /** 增加失败登录次数 */
    void incrementFailedLoginCount(@Param("userId") String userId);

    /** 重置失败登录次数 */
    void resetFailedLoginCount(@Param("userId") String userId);

    /** 锁定账号到指定时间 */
    void lockAccount(@Param("userId") String userId, @Param("lockUntilTime") LocalDateTime lockUntilTime);

    /**
     * 原子性解锁已过期的锁定账号（防并发）。
     * <p>
     * 当 status=2 且 lock_until_time 已到期时，一次性恢复为正常状态。
     * 使用 WHERE 条件保证原子性，避免读→判断→写的竞态条件。
     *
     * @param userId 用户ID（cas_user.id，而非 user_id 业务标识）
     * @return 影响行数：1=已解锁，0=未解锁（非锁定或未到期）
     */
    /**
     * 分页查询用户列表
     */
    List<UnifiedUserDO> selectAllPaged(@Param("query") String query,
                                       @Param("status") Integer status,
                                       @Param("offset") long offset,
                                       @Param("limit") int limit);

    /**
     * 统计符合条件的用户数
     */
    long countByQuery(@Param("query") String query, @Param("status") Integer status);

    /**
     * 根据角色查询用户列表
     */
    List<UnifiedUserDO> selectByRole(@Param("role") String role);

    /**
     * 为用户追加角色（仅当角色不存在时追加）
     */
    int addRole(@Param("userId") String userId, @Param("role") String role);

    /**
     * 为用户移除角色
     */
    int removeRole(@Param("userId") String userId, @Param("role") String role);

    /**
     * 更新必须修改密码标记
     */
    void updateMustChangePassword(@Param("userId") String userId, @Param("mustChangePassword") boolean mustChangePassword);

    int unlockAccountIfNeeded(@Param("userId") Long userId);
}
