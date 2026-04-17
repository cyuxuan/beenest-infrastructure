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

    /** 批量查询用户（用于用户同步 API，避免 N+1 问题） */
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
}
