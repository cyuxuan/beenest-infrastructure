package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.*;

/**
 * CAS 用户管理 Actuator 端点。
 * <p>
 * 提供 cas_user 表的查看、管理（禁用/启用/解锁/重置密码）和邀请注册功能。
 * Palantir 前端通过 actuatorEndpoints.casUsers 调用。
 */
@Slf4j
@Endpoint(id = "casUsers")
public class CasUsersEndpoint {

    private final UnifiedUserMapper userMapper;
    private final AccountRegistrationProvisioner registrationProvisioner;

    public CasUsersEndpoint(UnifiedUserMapper userMapper,
                            AccountRegistrationProvisioner registrationProvisioner) {
        this.userMapper = userMapper;
        this.registrationProvisioner = registrationProvisioner;
    }

    /**
     * 列出用户（分页、搜索、状态过滤）
     */
    @ReadOperation
    public Map<String, Object> listUsers(String query, Integer status, Integer page, Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        long offset = (long) p * s;

        List<UnifiedUserDO> users = userMapper.selectAllPaged(query, status, offset, s + 1);
        long total = userMapper.countByQuery(query, status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users.subList(0, Math.min(users.size(), s)));
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        result.put("hasMore", users.size() > s);
        return result;
    }

    /**
     * 获取用户详情
     */
    @ReadOperation
    public Map<String, Object> getUser(@Selector String userId) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }
        return toUserMap(user);
    }

    /**
     * 管理员添加用户（创建邀请）
     */
    @WriteOperation
    public Map<String, Object> createInvitation(@Selector String username,
                                                 String email,
                                                 String phone,
                                                 String roles) {
        // 1. 构造 CAS 原生 AccountRegistrationRequest
        var request = new AccountRegistrationRequest();
        request.getProperties().put("username", username);
        // 管理员创建的用户需要后续设置密码，先填一个随机值
        request.getProperties().put("password", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        request.getProperties().put("userType", "CUSTOMER");
        if (StringUtils.isNotBlank(email)) {
            request.getProperties().put("email", email);
        }
        if (StringUtils.isNotBlank(phone)) {
            request.getProperties().put("phone", phone);
        }
        if (StringUtils.isNotBlank(roles)) {
            request.getProperties().put("roles", roles);
        }

        // 2. 调用注册落库器
        AccountRegistrationResponse response;
        try {
            response = registrationProvisioner.provision(request);
        } catch (Throwable e) {
            LOGGER.error("管理员邀请注册异常: username={}", username, e);
            return Map.of("success", false, "message", "注册失败：" + e.getMessage());
        }
        boolean success = response.isSuccess();

        if (success) {
            String userId = response.getProperty("userId", String.class);
            LOGGER.info("管理员邀请注册成功: username={}, email={}, userId={}", username, email, userId);
            return Map.of(
                    "success", true,
                    "userId", userId != null ? userId : "",
                    "message", "用户创建成功"
            );
        }

        LOGGER.warn("管理员邀请注册失败: username={}", username);
        String message = response.getProperty("message", String.class);
        return Map.of(
                "success", false,
                "message", message != null ? message : "注册失败"
        );
    }

    /**
     * 更新用户状态（禁用/启用/解锁/强制改密）
     */
    @WriteOperation
    public Map<String, Object> updateUser(@Selector String userId,
                                           Integer status,
                                           String action,
                                           Boolean mustChangePassword) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }

        // 1. 状态变更
        if (status != null) {
            if (status != CasConstant.USER_STATUS_ACTIVE
                    && status != CasConstant.USER_STATUS_DISABLED
                    && status != CasConstant.USER_STATUS_LOCKED) {
                return Map.of("error", "无效的状态值", "status", status);
            }
            userMapper.updateStatus(userId, status);
            LOGGER.info("用户状态变更: userId={}, status={}", userId, status);
        }

        // 2. 解锁操作
        if ("unlock".equals(action)) {
            userMapper.resetFailedLoginCount(userId);
            userMapper.updateStatus(userId, CasConstant.USER_STATUS_ACTIVE);
            LOGGER.info("管理员解锁账号: userId={}", userId);
        }

        // 3. 强制改密
        if (Boolean.TRUE.equals(mustChangePassword)) {
            userMapper.updateMustChangePassword(userId, true);
            LOGGER.info("管理员强制用户改密: userId={}", userId);
        }

        UnifiedUserDO updated = userMapper.selectByUserId(userId);
        return toUserMap(updated);
    }

    private Map<String, Object> toUserMap(UnifiedUserDO user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("phone", user.getPhone());
        map.put("email", user.getEmail());
        map.put("userType", user.getUserType());
        map.put("status", user.getStatus());
        map.put("roles", user.getRoles());
        map.put("lastLoginTime", user.getLastLoginTime() != null ? user.getLastLoginTime().toString() : null);
        map.put("createdTime", user.getCreatedTime() != null ? user.getCreatedTime().toString() : null);
        map.put("failedLoginCount", user.getFailedLoginCount());
        map.put("lockUntilTime", user.getLockUntilTime() != null ? user.getLockUntilTime().toString() : null);
        return map;
    }
}