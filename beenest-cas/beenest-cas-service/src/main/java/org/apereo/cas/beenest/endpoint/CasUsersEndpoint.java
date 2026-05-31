package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.web.BaseCasRestActuatorEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

/**
 * CAS 用户管理 Actuator 端点。
 * <p>
 * 提供 cas_user 表的查看、管理（禁用/启用/解锁/重置密码）和邀请注册功能。
 * Palantir 前端通过 actuatorEndpoints.casUsers 调用。
 */
@Slf4j
@Endpoint(id = "casUsers", defaultAccess = Access.NONE)
public class CasUsersEndpoint extends BaseCasRestActuatorEndpoint {

    private final UnifiedUserMapper userMapper;
    private final AccountRegistrationProvisioner registrationProvisioner;

    public CasUsersEndpoint(final CasConfigurationProperties casProperties,
                            final ConfigurableApplicationContext applicationContext,
                            final UnifiedUserMapper userMapper,
                            final AccountRegistrationProvisioner registrationProvisioner) {
        super(casProperties, applicationContext);
        this.userMapper = userMapper;
        this.registrationProvisioner = registrationProvisioner;
    }

    /**
     * 列出用户（分页、搜索、状态过滤）
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> listUsers(@RequestParam(value = "query", required = false) String query,
                                         @RequestParam(value = "status", required = false) Integer status,
                                         @RequestParam(value = "page", required = false) Integer page,
                                         @RequestParam(value = "size", required = false) Integer size) {
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
    @GetMapping(value = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getUser(@PathVariable("userId") String userId) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }
        return toUserMap(user);
    }

    /**
     * 管理员添加用户（创建邀请）
     */
    @PostMapping(value = "/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> createInvitation(@PathVariable("username") String username,
                                                @RequestParam(value = "email", required = false) String email,
                                                @RequestParam(value = "phone", required = false) String phone,
                                                @RequestParam(value = "roles", required = false) String roles) {
        // 1. 构造 CAS 原生 AccountRegistrationRequest
        var request = new AccountRegistrationRequest();
        request.getProperties().put("username", username);
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

        // 2. 调用原生注册落库器
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
    @PostMapping(value = "/{userId}/update", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> updateUser(@PathVariable("userId") String userId,
                                          @RequestParam(value = "status", required = false) Integer status,
                                          @RequestParam(value = "action", required = false) String action,
                                          @RequestParam(value = "mustChangePassword", required = false) Boolean mustChangePassword) {
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