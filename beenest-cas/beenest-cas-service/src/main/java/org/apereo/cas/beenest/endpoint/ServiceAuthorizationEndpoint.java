package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.*;

/**
 * 服务授权管理 Actuator 端点。
 * <p>
 * 管理用户对服务的访问权限，底层操作 cas_user.roles 字段。
 * 角色名从 Service JSON 的 accessStrategy.requiredAttributes.memberOf 提取。
 */
@Slf4j
@Endpoint(id = "serviceAuthorization")
public class ServiceAuthorizationEndpoint {

    private final ServicesManager servicesManager;
    private final UnifiedUserMapper userMapper;

    public ServiceAuthorizationEndpoint(ServicesManager servicesManager,
                                         UnifiedUserMapper userMapper) {
        this.servicesManager = servicesManager;
        this.userMapper = userMapper;
    }

    /**
     * 列出所有已注册应用及其角色要求
     */
    @ReadOperation
    public List<Map<String, Object>> listServices() {
        var services = new ArrayList<Map<String, Object>>();
        for (RegisteredService svc : servicesManager.getAllServices()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", svc.getId());
            info.put("name", svc.getName());
            info.put("serviceId", svc.getServiceId());
            info.put("description", svc.getDescription());
            info.put("requiredRole", extractRequiredRole(svc));
            services.add(info);
        }
        services.sort(Comparator.comparingLong(m -> (Long) m.get("id")));
        return services;
    }

    /**
     * 列出有权限访问该服务的用户
     */
    @ReadOperation
    public Map<String, Object> getServiceUsers(@Selector long serviceId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            // 无角色要求 = 所有已认证用户可访问
            return Map.of("serviceId", serviceId, "name", svc.getName(),
                    "requiredRole", "无", "users", List.of(), "openAccess", true);
        }

        List<UnifiedUserDO> users = userMapper.selectByRole(role);
        List<Map<String, Object>> userList = new ArrayList<>();
        for (UnifiedUserDO u : users) {
            userList.add(toUserInfo(u));
        }
        return Map.of("serviceId", serviceId, "name", svc.getName(),
                "requiredRole", role, "users", userList, "openAccess", false);
    }

    /**
     * 授权用户访问服务
     */
    @WriteOperation
    public Map<String, Object> grantAccess(@Selector long serviceId,
                                            String userId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return Map.of("error", "该服务无角色要求，所有已认证用户均可访问", "serviceId", serviceId);
        }

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }

        int updated = userMapper.addRole(userId, role);
        if (updated > 0) {
            LOGGER.info("授权用户访问服务: userId={}, serviceId={}, role={}", userId, serviceId, role);
        }
        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        return Map.of("success", true, "userId", userId, "roles", refreshed.getRoles());
    }

    /**
     * 撤销用户服务访问
     */
    @DeleteOperation
    public Map<String, Object> revokeAccess(@Selector long serviceId,
                                             String userId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return Map.of("error", "该服务无角色要求", "serviceId", serviceId);
        }

        int updated = userMapper.removeRole(userId, role);
        if (updated > 0) {
            LOGGER.info("撤销用户服务访问: userId={}, serviceId={}, role={}", userId, serviceId, role);
        }
        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        return Map.of("success", true, "userId", userId, "roles", refreshed.getRoles());
    }

    /**
     * 从 Service JSON 的 accessStrategy.requiredAttributes.memberOf 提取角色名
     */
    private String extractRequiredRole(RegisteredService svc) {
        try {
            var strategy = svc.getAccessStrategy();
            if (strategy == null) return null;
            var requiredAttrs = strategy.getRequiredAttributes();
            if (requiredAttrs == null) return null;
            var memberOfValues = requiredAttrs.get("memberOf");
            if (memberOfValues == null || memberOfValues.isEmpty()) return null;
            return memberOfValues.iterator().next().toString();
        } catch (Exception e) {
            LOGGER.debug("提取 Service 角色失败: serviceId={}, error={}", svc.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toUserInfo(UnifiedUserDO user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("userType", user.getUserType());
        map.put("status", user.getStatus());
        map.put("roles", user.getRoles());
        return map;
    }
}