package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.CreateUserRequestDTO;
import org.apereo.cas.beenest.dto.RoleChangeRequestDTO;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.vo.OperationResultVO;
import org.apereo.cas.beenest.vo.UserDetailVO;
import org.apereo.cas.beenest.vo.UserDetailVOConverter;
import org.apereo.cas.beenest.vo.UserListVO;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

/**
 * CAS 用户管理 Actuator 端点。
 * <p>
 * 提供 cas_user 表的查看、管理（禁用/启用/解锁/重置密码）和创建用户功能。
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
    public R<UserListVO> listUsers(@RequestParam(value = "query", required = false) String query,
                                  @RequestParam(value = "status", required = false) Integer status,
                                  @RequestParam(value = "page", required = false) Integer page,
                                  @RequestParam(value = "size", required = false) Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        long offset = (long) p * s;

        List<UnifiedUserDO> users = userMapper.selectAllPaged(query, status, offset, s + 1);
        long total = userMapper.countByQuery(query, status);

        UserListVO vo = new UserListVO();
        vo.setUsers(users.subList(0, Math.min(users.size(), s)).stream()
                .map(UserDetailVOConverter::toVO).toList());
        vo.setTotal(total);
        vo.setPage(p);
        vo.setSize(s);
        vo.setHasMore(users.size() > s);
        return R.ok(vo);
    }

    /**
     * 获取用户详情
     */
    @GetMapping(value = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<UserDetailVO> getUser(@PathVariable("userId") String userId) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }
        return R.ok(UserDetailVOConverter.toVO(user));
    }

    /**
     * 管理员创建用户。
     * <p>
     * 通过 CAS 原生 AccountRegistrationProvisioner 创建用户，
     * 自动执行去重校验、密码加密、自动赋权等逻辑。
     */
    @PostMapping(value = "/create", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<OperationResultVO> createUser(@RequestBody CreateUserRequestDTO request) {
        // 1. 构造 CAS 原生 AccountRegistrationRequest
        var regRequest = new AccountRegistrationRequest();
        regRequest.getProperties().put("username", request.getUsername());
        regRequest.getProperties().put("password", request.getPassword());
        if (StringUtils.isNotBlank(request.getEmail())) {
            regRequest.getProperties().put("email", request.getEmail());
        }
        if (StringUtils.isNotBlank(request.getPhone())) {
            regRequest.getProperties().put("phone", request.getPhone());
        }
        if (StringUtils.isNotBlank(request.getFirstName())) {
            regRequest.getProperties().put("firstName", request.getFirstName());
        }
        if (StringUtils.isNotBlank(request.getLastName())) {
            regRequest.getProperties().put("lastName", request.getLastName());
        }
        if (StringUtils.isNotBlank(request.getUserType())) {
            regRequest.getProperties().put("userType", request.getUserType());
        }

        // 2. 调用原生注册落库器
        AccountRegistrationResponse response;
        try {
            response = registrationProvisioner.provision(regRequest);
        } catch (Throwable e) {
            LOGGER.error("管理员创建用户异常: username={}", request.getUsername(), e);
            return R.fail("创建失败：" + e.getMessage());
        }

        if (response.isSuccess()) {
            String userId = response.getProperty("userId", String.class);
            LOGGER.info("管理员创建用户成功: username={}, userId={}", request.getUsername(), userId);
            return R.ok(OperationResultVO.of("用户创建成功", userId != null ? userId : ""));
        }

        LOGGER.warn("管理员创建用户失败: username={}", request.getUsername());
        String message = response.getProperty("message", String.class);
        return R.fail(message != null ? message : "创建失败");
    }

    /**
     * 为用户追加角色
     */
    @PostMapping(value = "/addRole", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<OperationResultVO> addRole(@RequestBody RoleChangeRequestDTO request) {
        String userId = request.getUserId();
        String role = StringUtils.upperCase(request.getRole());

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }

        userMapper.addRole(userId, role);
        LOGGER.info("追加角色成功: userId={}, role={}", userId, role);

        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        var result = OperationResultVO.of("角色追加成功", userId);
        result.setRoles(UserDetailVOConverter.parseRoles(refreshed.getRoles()));
        return R.ok(result);
    }

    /**
     * 为用户移除角色
     */
    @PostMapping(value = "/removeRole", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<OperationResultVO> removeRole(@RequestBody RoleChangeRequestDTO request) {
        String userId = request.getUserId();
        String role = StringUtils.upperCase(request.getRole());

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }

        userMapper.removeRole(userId, role);
        LOGGER.info("移除角色成功: userId={}, role={}", userId, role);

        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        var result = OperationResultVO.of("角色移除成功", userId);
        result.setRoles(UserDetailVOConverter.parseRoles(refreshed.getRoles()));
        return R.ok(result);
    }

    /**
     * 更新用户状态（禁用/启用/解锁/强制改密）
     */
    @PostMapping(value = "/{userId}/update", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public R<UserDetailVO> updateUser(@PathVariable("userId") String userId,
                                      @RequestParam(value = "status", required = false) Integer status,
                                      @RequestParam(value = "action", required = false) String action,
                                      @RequestParam(value = "mustChangePassword", required = false) Boolean mustChangePassword) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }

        // 1. 状态变更
        if (status != null) {
            if (status != CasConstant.USER_STATUS_ACTIVE
                    && status != CasConstant.USER_STATUS_DISABLED
                    && status != CasConstant.USER_STATUS_LOCKED) {
                return R.fail(400, "无效的状态值");
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
        return R.ok(UserDetailVOConverter.toVO(updated));
    }
}