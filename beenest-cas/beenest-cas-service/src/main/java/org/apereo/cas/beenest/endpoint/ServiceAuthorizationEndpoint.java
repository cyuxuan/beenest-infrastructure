package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.AccessRequestDTO;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.vo.OperationResultVO;
import org.apereo.cas.beenest.vo.ServiceInfoVO;
import org.apereo.cas.beenest.vo.ServiceUsersVO;
import org.apereo.cas.beenest.vo.UserDetailVO;
import org.apereo.cas.beenest.vo.UserDetailVOConverter;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.web.BaseCasRestActuatorEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 服务授权管理 Actuator 端点。
 * <p>
 * 管理用户对服务的访问权限，底层操作 cas_user.roles 字段。
 * 角色名从 Service JSON 的 accessStrategy.requiredAttributes.memberOf 提取。
 */
@Slf4j
@Endpoint(id = "serviceAuthorization", defaultAccess = Access.NONE)
public class ServiceAuthorizationEndpoint extends BaseCasRestActuatorEndpoint {

    private final ServicesManager servicesManager;
    private final UnifiedUserMapper userMapper;

    public ServiceAuthorizationEndpoint(final CasConfigurationProperties casProperties,
                                         final ConfigurableApplicationContext applicationContext,
                                         final ServicesManager servicesManager,
                                         final UnifiedUserMapper userMapper) {
        super(casProperties, applicationContext);
        this.servicesManager = servicesManager;
        this.userMapper = userMapper;
    }

    /**
     * 列出所有已注册应用及其角色要求
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<List<ServiceInfoVO>> listServices() {
        var result = new ArrayList<ServiceInfoVO>();
        for (RegisteredService svc : servicesManager.getAllServices()) {
            ServiceInfoVO vo = new ServiceInfoVO();
            vo.setId(svc.getId());
            vo.setName(svc.getName());
            vo.setServiceId(svc.getServiceId());
            vo.setDescription(svc.getDescription());
            vo.setRequiredRole(extractRequiredRole(svc));
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(ServiceInfoVO::getId));
        return R.ok(result);
    }

    /**
     * 列出有权限访问该服务的用户
     */
    @GetMapping(value = "/{serviceId}/users", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<ServiceUsersVO> getServiceUsers(@PathVariable("serviceId") long serviceId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return R.fail(404, "服务不存在");
        }

        String role = extractRequiredRole(svc);
        ServiceUsersVO vo = new ServiceUsersVO();
        vo.setServiceId(serviceId);
        vo.setName(svc.getName());
        vo.setRequiredRole(role);

        if (role == null) {
            vo.setOpenAccess(true);
            vo.setUsers(List.of());
            return R.ok(vo);
        }

        vo.setOpenAccess(false);
        List<UnifiedUserDO> users = userMapper.selectByRole(role);
        vo.setUsers(users.stream().map(UserDetailVOConverter::toVO).toList());
        return R.ok(vo);
    }

    /**
     * 搜索用户（用于授权对话框）
     */
    @GetMapping(value = "/searchUsers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<List<UserDetailVO>> searchUsers(@RequestParam("q") String query) {
        List<UnifiedUserDO> users = userMapper.selectAllPaged(query, null, 0, 10);
        return R.ok(users.stream().map(UserDetailVOConverter::toVO).toList());
    }

    /**
     * 授权用户访问服务
     */
    @PostMapping(value = "/grant", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<OperationResultVO> grantAccess(@RequestBody AccessRequestDTO request) {
        long serviceId = request.getServiceId();
        String userId = request.getUserId();

        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return R.fail(404, "服务不存在");
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return R.fail(400, "该服务无角色要求，所有已认证用户均可访问");
        }

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }

        userMapper.addRole(userId, role);
        // 权限变更后递增 tokenVersion，使已发放的 Token 在刷新时触发重新检查
        userMapper.incrementTokenVersion(userId);
        LOGGER.info("授权用户访问服务: userId={}, serviceId={}, role={}", userId, serviceId, role);

        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        var result = OperationResultVO.of("授权成功", userId);
        result.setRoles(UserDetailVOConverter.parseRoles(refreshed.getRoles()));
        return R.ok(result);
    }

    /**
     * 撤销用户服务访问
     */
    @PostMapping(value = "/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<OperationResultVO> revokeAccess(@RequestBody AccessRequestDTO request) {
        long serviceId = request.getServiceId();
        String userId = request.getUserId();

        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return R.fail(404, "服务不存在");
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return R.fail(400, "该服务无角色要求");
        }

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }

        userMapper.removeRole(userId, role);
        // 权限变更后递增 tokenVersion，使已发放的 Token 在刷新时触发重新检查
        userMapper.incrementTokenVersion(userId);
        LOGGER.info("撤销用户服务访问: userId={}, serviceId={}, role={}", userId, serviceId, role);

        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        var result = OperationResultVO.of("撤销成功", userId);
        result.setRoles(UserDetailVOConverter.parseRoles(refreshed.getRoles()));
        return R.ok(result);
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
            return memberOfValues.iterator().next();
        } catch (Exception e) {
            LOGGER.debug("提取 Service 角色失败: serviceId={}, error={}", svc.getId(), e.getMessage());
            return null;
        }
    }
}
