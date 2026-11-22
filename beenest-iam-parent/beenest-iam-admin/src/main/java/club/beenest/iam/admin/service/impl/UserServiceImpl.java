package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.converter.UserDtoConverter;
import club.beenest.iam.admin.dto.AssignRolesRequest;
import club.beenest.iam.admin.dto.RequiredActionsRequest;
import club.beenest.iam.admin.dto.ResetPasswordRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.UserBatchStatusRequest;
import club.beenest.iam.admin.dto.UserCreateRequest;
import club.beenest.iam.admin.dto.UserOrganizationResponse;
import club.beenest.iam.admin.dto.UserResponse;
import club.beenest.iam.admin.dto.UserUpdateRequest;
import club.beenest.iam.admin.service.UserService;
import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.security.PasswordChangeService;
import club.beenest.iam.authz.security.PasswordPolicyService;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.token.TokenStateCache;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.manager.IamPasswordHistoryManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现。
 * <p>所有按主键操作的方法都会先校验目标用户属于调用者所在域,
 * 防止通过猜测主键跨域读取或修改他人数据。</p>
 */
@Service
public class UserServiceImpl implements UserService {

    /** 账号状态:正常 */
    private static final int STATUS_NORMAL = 1;
    /** 账号状态:禁用 */
    private static final int STATUS_DISABLED = 0;
    /** 账号状态:锁定 */
    private static final int STATUS_LOCKED = 2;
    /** 角色容器类型:应用角色 */
    private static final String CONTAINER_APP = "APP";

    private final IamUserManager userManager;
    private final IamRoleManager roleManager;
    private final IamGroupManager groupManager;
    private final IamPositionManager positionManager;
    private final UserDtoConverter dtoConverter;
    private final RoleDtoConverter roleDtoConverter;
    private final TokenStateCache tokenStateCache;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final PasswordPolicyService passwordPolicyService;
    private final IamPasswordHistoryManager passwordHistoryManager;
    private final PasswordChangeService passwordChangeService;
    private final PasswordEncoder passwordEncoder;
    private final LoginFailureCounter loginFailureCounter;
    private final AuthService authService;
    private final RequiredActionService requiredActionService;
    private final SecurityAlertService securityAlertService;

    public UserServiceImpl(IamUserManager userManager, IamRoleManager roleManager,
                           IamGroupManager groupManager, IamPositionManager positionManager,
                           UserDtoConverter dtoConverter, RoleDtoConverter roleDtoConverter,
                           TokenStateCache tokenStateCache,
                           IamSecurityPolicyManager securityPolicyManager,
                           PasswordPolicyService passwordPolicyService,
                           IamPasswordHistoryManager passwordHistoryManager,
                           PasswordChangeService passwordChangeService,
                           PasswordEncoder passwordEncoder,
                           LoginFailureCounter loginFailureCounter,
                           AuthService authService,
                           RequiredActionService requiredActionService,
                           SecurityAlertService securityAlertService) {
        this.userManager = userManager;
        this.roleManager = roleManager;
        this.groupManager = groupManager;
        this.positionManager = positionManager;
        this.dtoConverter = dtoConverter;
        this.roleDtoConverter = roleDtoConverter;
        this.tokenStateCache = tokenStateCache;
        this.securityPolicyManager = securityPolicyManager;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordHistoryManager = passwordHistoryManager;
        this.passwordChangeService = passwordChangeService;
        this.passwordEncoder = passwordEncoder;
        this.loginFailureCounter = loginFailureCounter;
        this.authService = authService;
        this.requiredActionService = requiredActionService;
        this.securityAlertService = securityAlertService;
    }

    @Override
    public PageResult<UserResponse> page(String username, Integer status, int pageNum, int pageSize) {
        // 1. 组装单表查询条件
        User condition = new User();
        condition.setUsername(username);
        condition.setStatus(status);
        // 2. 分页查询当前域用户后逐条转换
        PageResult<User> page = userManager.page(currentRealmId(), condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(dtoConverter::toResponse).toList());
    }

    @Override
    public UserResponse get(Long id) {
        return dtoConverter.toResponse(requireUserInRealm(id));
    }

    @Override
    @Transactional
    public UserResponse create(UserCreateRequest request) {
        Long realmId = currentRealmId();
        // 1. 登录名在域内唯一
        if (userManager.getByUsername(realmId, request.username()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "登录名已存在: " + request.username());
        }
        // 2. 已验证手机号在域内唯一:重复会让短信登录按手机号定位账号时产生歧义
        requirePhoneAvailable(realmId, null, request.phone(), request.phoneVerified());
        // 3. 口令按域策略校验(复杂度)
        if (request.password() != null && !request.password().isBlank()) {
            passwordPolicyService.validate(request.password(),
                    securityPolicyManager.ensurePasswordPolicy(realmId));
        }
        // 3. 落库用户与口令凭证(同一事务)
        User user = dtoConverter.toEntity(request, realmId);
        userManager.create(user, request.password());
        // 4. 记录口令历史,使历史校验对新建账号同样生效
        if (request.password() != null && !request.password().isBlank()) {
            PasswordPolicy policy = securityPolicyManager.ensurePasswordPolicy(realmId);
            int keep = policy.getHistoryCount() == null ? 0 : policy.getHistoryCount();
            passwordHistoryManager.record(realmId, user.getId(),
                    passwordEncoder.encode(request.password()), keep);
            // 5. 初始口令由管理员设定,首次登录必须改掉
            requiredActionService.requirePasswordChange(user.getId());
        }
        // 6. 告知本人账号已开通:邮件里不带初始口令,引导其用"忘记口令"自助设置,
        //    管理员也就不需要再通过线下渠道把口令传出去
        securityAlertService.accountOpened(realmId, user.getId());
        return dtoConverter.toResponse(userManager.getById(user.getId()));
    }

    @Override
    public UserResponse update(Long id, UserUpdateRequest request) {
        // 1. 目标用户必须在当前域
        User user = requireUserInRealm(id);
        // 2. 仅更新显式给出的字段
        dtoConverter.applyUpdate(user, request);
        // 3. 落库前校验已验证手机号的域内唯一性(用更新后的最终值判定)
        requirePhoneAvailable(currentRealmId(), id, user.getPhone(), user.getPhoneVerified());
        userManager.update(user);
        // 3. 账号状态变更会影响已签发令牌的有效性,需让验签侧立即感知
        if (request.status() != null) {
            tokenStateCache.invalidate(id);
        }
        return dtoConverter.toResponse(userManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        // 1. 目标用户必须在当前域
        User user = requireUserInRealm(id);
        // 2. 不允许删除当前登录账号
        if (user.getId().equals(currentPrincipal().getUserId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "不允许删除当前登录账号");
        }
        // 3. 逻辑删除并停用凭证
        userManager.remove(id);
        // 4. 令牌状态缓存失效:删号后其已签发令牌必须立即失效
        tokenStateCache.invalidate(id);
        // 5. 权限缓存:删号不影响他人的权限,无需额外失效(代次机制见 IamPermissionEpoch)
    }

    @Override
    public void resetPassword(Long id, ResetPasswordRequest request) {
        // 1. 目标用户必须在当前域
        requireUserInRealm(id);
        // 2. 按域策略重置:复杂度/历史校验 + 写入新凭证 + 记录历史 + 令牌版本递增
        //    重置口令经管理员之手,下次登录强制改密,不作为长期凭据
        passwordChangeService.resetPassword(currentRealmId(), id, request.newPassword());
        // 3. 令牌状态缓存失效,验签立即拒绝旧令牌
        tokenStateCache.invalidate(id);
    }

    @Override
    public void assignRoles(Long id, AssignRolesRequest request) {
        // 1. 目标用户必须在当前域
        requireUserInRealm(id);
        IamUserPrincipal operator = currentPrincipal();
        // 1.1 不允许给自己分配角色:持 user:assign-role 的低权管理员可借此绕过权限上限直接提权
        if (id.equals(operator.getUserId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "不允许修改当前登录账号的角色");
        }
        // 2. 待分配角色必须全部属于当前域,防止把别域角色挂到本域用户上
        for (Long roleId : request.roleIds()) {
            Role role = requireRoleInRealm(roleId);
            // 应用角色必须绑定本域应用,否则令牌无法判定它属于哪个应用
            if (CONTAINER_APP.equals(role.getContainerType()) && role.getAppServiceId() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "应用角色缺少所属应用: " + roleId);
            }
        }
        // 3. 覆盖式分配角色,操作人记入关联行便于审计
        roleManager.assignRoles(operator.getRealmId(), id, request.roleIds(), operator.getUserId());
        // 4. 权限集合已变化:角色分配在持久层已提升权限代次,该域缓存即刻失效
    }

    @Override
    public List<RoleResponse> listRoles(Long id) {
        requireUserInRealm(id);
        return roleManager.listRolesByUserId(id).stream()
                .map(roleDtoConverter::toResponse)
                .toList();
    }

    @Override
    public List<UserOrganizationResponse> listOrganizations(Long id) {
        User user = requireUserInRealm(id);
        List<UserGroup> relations = groupManager.listRelationsByUserId(user.getId());
        if (relations.isEmpty()) {
            return List.of();
        }
        // 部门与岗位各一次批量取回:逐条查是 N+1
        Map<Long, Group> groups = groupManager.listByRealm(user.getRealmId()).stream()
                .collect(Collectors.toMap(Group::getId, Function.identity(), (first, second) -> first));
        Map<Long, Position> positions = positionManager.listByIds(
                        relations.stream().map(UserGroup::getPositionId)
                                .filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Position::getId, Function.identity(), (first, second) -> first));
        List<UserOrganizationResponse> rows = new ArrayList<>();
        for (UserGroup relation : relations) {
            Group group = groups.get(relation.getGroupId());
            if (group == null) {
                continue;
            }
            Position position = relation.getPositionId() == null
                    ? null : positions.get(relation.getPositionId());
            rows.add(new UserOrganizationResponse(group.getId(), group.getGroupCode(), group.getGroupName(),
                    group.getGroupType(),
                    position == null ? null : position.getId(),
                    position == null ? null : position.getPositionCode(),
                    position == null ? null : position.getPositionName(),
                    Boolean.TRUE.equals(relation.getIsPrimary())));
        }
        // 主部门排在最前,其余按部门编码排序
        rows.sort(Comparator.comparing((UserOrganizationResponse row) -> !row.primary())
                .thenComparing(UserOrganizationResponse::groupCode));
        return List.copyOf(rows);
    }

    @Override
    public void lock(Long id) {
        // 1. 目标用户必须在当前域
        User user = requireUserInRealm(id);
        // 2. 不允许锁定当前登录账号
        if (user.getId().equals(currentPrincipal().getUserId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "不允许锁定当前登录账号");
        }
        // 3. 状态置为锁定;令牌状态缓存失效,验签立即拒绝其已签发令牌
        user.setStatus(STATUS_LOCKED);
        userManager.update(user);
        tokenStateCache.invalidate(id);
        // 4. 清理失败计数:锁定是管理动作,不应叠加登录策略的自动计数
        loginFailureCounter.unlock(currentPrincipal().getRealmKey(), user.getUsername());
    }

    @Override
    public void unlock(Long id) {
        // 1. 目标用户必须在当前域
        User user = requireUserInRealm(id);
        // 2. 状态恢复正常
        user.setStatus(STATUS_NORMAL);
        userManager.update(user);
        tokenStateCache.invalidate(id);
        // 3. 清除失败计数与临时锁定标记:策略驱动的自动锁定由这里解除,
        //    管理员解锁后用户从零开始重新累计
        loginFailureCounter.unlock(currentPrincipal().getRealmKey(), user.getUsername());
    }

    @Override
    public int logoutAll(Long id) {
        // 1. 目标用户必须在当前域
        requireUserInRealm(id);
        // 2. 复用认证侧编排:令牌版本递增 + 全部会话置为已注销 + 权限缓存失效
        return authService.logoutAll(currentRealmId(), currentPrincipal().getRealmKey(), id);
    }

    @Override
    public int batchStatus(UserBatchStatusRequest request) {
        Long realmId = currentRealmId();
        // 1. 只接受启用/禁用两种目标状态;锁定是带副作用的管理动作,必须逐个执行
        if (request.status() == null
                || (request.status() != STATUS_NORMAL && request.status() != STATUS_DISABLED)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "批量操作仅支持启用(1)或禁用(0)");
        }
        // 2. 校验域归属并收集属于本域的主键(他域主键按不存在处理,不报错不遗漏)。
        //    一次批量取回,逐条查会把一次批量操作放大成 N 次查询
        Set<Long> ownedIds = userManager.listByIds(request.ids()).stream()
                .filter(user -> realmId.equals(user.getRealmId()))
                .map(User::getId)
                .collect(Collectors.toSet());
        List<Long> ids = request.ids().stream().filter(ownedIds::contains).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        // 3. 批量更新状态
        int updated = userManager.updateStatusByIds(ids, request.status());
        // 4. 令牌状态缓存逐个失效:禁用/启用都会改变已签发令牌的有效性
        for (Long id : ids) {
            tokenStateCache.invalidate(id);
        }
        return updated;
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return currentPrincipal().getRealmId();
    }

    /**
     * 当前调用者主体。
     *
     * @return 用户主体
     */
    private IamUserPrincipal currentPrincipal() {
        return CurrentUserHolder.require();
    }

    @Override
    public List<String> listRequiredActions(Long id) {
        requireUserInRealm(id);
        return requiredActionService.pending(id).stream().map(Enum::name).toList();
    }

    @Override
    public void setRequiredActions(Long id, RequiredActionsRequest request) {
        requireUserInRealm(id);
        // 未知编码直接拒绝,不做静默丢弃
        Set<RequiredAction> actions = new LinkedHashSet<>();
        for (String code : request.actions()) {
            RequiredAction action = RequiredAction.of(code);
            if (action == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知的待办事项: " + code);
            }
            actions.add(action);
        }
        userManager.updateRequiredActions(id, actions);
    }

    /**
     * 读取属于当前域的用户,不存在或跨域一律按不存在处理。
     *
     * @param id 用户主键
     * @return 用户
     * @throws BusinessException 用户不存在或不属于当前域
     */
    private User requireUserInRealm(Long id) {        User user = userManager.getById(id);
        if (user == null || !currentRealmId().equals(user.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + id);
        }
        return user;
    }

    /**
     * 校验角色属于当前域,不存在或跨域一律按不存在处理。
     *
     * @param roleId 角色主键
     * @return 校验通过的角色
     * @throws BusinessException 角色不存在或不属于当前域
     */
    private Role requireRoleInRealm(Long roleId) {
        Role role = roleManager.getById(roleId);
        if (role == null || !currentRealmId().equals(role.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在: " + roleId);
        }
        return role;
    }

    /**
     * 校验已验证手机号在域内未被他人占用。
     * <p>仅已验证手机号参与唯一约束:验证态手机号是短信登录的定位键;
     * 未验证手机号可能是导入数据,不占位、不冲突。</p>
     *
     * @param realmId       身份域主键
     * @param excludeUserId 排除的用户主键,更新场景传自身,新建传 null
     * @param phone         最终生效的手机号
     * @param phoneVerified 最终生效的验证态
     * @throws BusinessException 手机号已被本域其他账号验证使用
     */
    private void requirePhoneAvailable(Long realmId, Long excludeUserId,
                                       String phone, Boolean phoneVerified) {
        if (!Boolean.TRUE.equals(phoneVerified) || phone == null || phone.isBlank()) {
            return;
        }
        User existing = userManager.getByPhone(realmId, phone);
        if (existing != null && !existing.getId().equals(excludeUserId)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                    "该手机号已被其他账号验证使用: " + phone);
        }
    }
}
