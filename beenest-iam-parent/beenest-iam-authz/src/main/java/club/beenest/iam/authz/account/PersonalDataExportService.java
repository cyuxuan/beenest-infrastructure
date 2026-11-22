package club.beenest.iam.authz.account;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.LoginEvent;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamLoginEventManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人数据导出服务。
 * <p>汇总当前登录用户名下的资料、角色、组、凭证概况、授权记录、登录设备与最近登录事件,
 * 以只读快照的形式返回。凭证明细只含元数据(名称、时间、状态),
 * 不含口令哈希、TOTP 密钥、恢复码哈希与 Passkey 公钥。</p>
 */
@Service
public class PersonalDataExportService {

    /** 导出中包含的最近登录事件条数 */
    private static final int RECENT_EVENT_LIMIT = 50;

    private final AccountOverviewService overviewService;
    private final IamUserManager userManager;
    private final UserProvider userProvider;
    private final IamEffectiveRoleResolver roleResolver;
    private final IamGroupManager groupManager;
    private final IamLoginEventManager loginEventManager;

    public PersonalDataExportService(AccountOverviewService overviewService, IamUserManager userManager,
                                     UserProvider userProvider, IamEffectiveRoleResolver roleResolver,
                                     IamGroupManager groupManager, IamLoginEventManager loginEventManager) {
        this.overviewService = overviewService;
        this.userManager = userManager;
        this.userProvider = userProvider;
        this.roleResolver = roleResolver;
        this.groupManager = groupManager;
        this.loginEventManager = loginEventManager;
    }

    /**
     * 导出当前登录用户的个人数据。
     *
     * @param currentSid 当前请求所用会话标识,用于标记设备列表中的"本机";可为 null
     * @return 有序的导出快照,可直接序列化为 JSON
     */
    public Map<String, Object> export(String currentSid) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        AccountOverview overview = overviewService.overview(currentSid);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportedAt", LocalDateTime.now());
        document.put("realm", principal.getRealmKey());
        document.put("account", account(principal));
        document.put("profile", profile(principal));
        document.put("roles", roles(principal));
        document.put("groups", groups(principal));
        document.put("credentials", credentials(overview));
        document.put("authorizedApplications", consents(overview));
        document.put("sessions", sessions(overview));
        document.put("recentLoginEvents", loginEvents(principal));
        return document;
    }

    /**
     * 账号主体标识。
     *
     * @param principal 当前登录主体
     * @return 用户主键与登录名
     */
    private static Map<String, Object> account(IamUserPrincipal principal) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("userId", principal.getUserId());
        account.put("username", principal.getUsername());
        return account;
    }

    /**
     * 资料与扩展属性。
     *
     * @param principal 当前登录主体
     * @return 资料快照;用户记录不存在时只含主体标识
     */
    private Map<String, Object> profile(IamUserPrincipal principal) {
        Map<String, Object> profile = new LinkedHashMap<>();
        User user = userManager.getById(principal.getUserId());
        if (user != null) {
            profile.put("username", user.getUsername());
            profile.put("nickname", user.getNickname());
            profile.put("email", user.getEmail());
            profile.put("emailVerified", Boolean.TRUE.equals(user.getEmailVerified()));
            profile.put("phone", user.getPhone());
            profile.put("phoneVerified", Boolean.TRUE.equals(user.getPhoneVerified()));
            profile.put("userType", user.getUserType());
            profile.put("createdTime", user.getCreatedTime());
        }
        profile.put("attributes", userProvider.findById(principal.getUserId())
                .map(UserModel::attributes)
                .orElse(Map.of()));
        return profile;
    }

    /**
     * 已授予的角色。
     *
     * @param principal 当前登录主体
     * @return 角色列表,元素含代码与名称;无角色时返回空列表
     */
    private List<Map<String, Object>> roles(IamUserPrincipal principal) {
        return roleResolver.resolve(principal.getRealmId(), principal.getUserId()).stream()
                .map(this::roleEntry)
                .toList();
    }

    /**
     * 角色实体转导出条目。
     *
     * @param role 角色实体
     * @return 含代码与名称的条目
     */
    private Map<String, Object> roleEntry(Role role) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", role.getRoleCode());
        entry.put("name", role.getRoleName());
        return entry;
    }

    /**
     * 所属用户组。
     *
     * @param principal 当前登录主体
     * @return 组列表,元素含代码与名称;无所属组时返回空列表
     */
    private List<Map<String, Object>> groups(IamUserPrincipal principal) {
        return groupManager.listByUserId(principal.getUserId()).stream()
                .map(this::groupEntry)
                .toList();
    }

    /**
     * 用户组实体转导出条目。
     *
     * @param group 用户组实体
     * @return 含代码与名称的条目
     */
    private Map<String, Object> groupEntry(Group group) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", group.getGroupCode());
        entry.put("name", group.getGroupName());
        return entry;
    }

    /**
     * 凭证概况(仅元数据)。
     *
     * @param overview 用户中心数据
     * @return 凭证条目
     */
    private static Map<String, Object> credentials(AccountOverview overview) {
        AccountOverview.Credentials source = overview.credentials();
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("passwordSet", source.passwordSet());
        credentials.put("totpEnabled", source.totpEnabled());
        credentials.put("recoveryCodesRemaining", source.recoveryCodesRemaining());
        credentials.put("passkeys", source.passkeys().stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("displayName", item.displayName());
                    entry.put("transports", item.transports());
                    entry.put("backupEligible", item.backupEligible());
                    entry.put("createdTime", item.createdTime());
                    entry.put("lastUsedTime", item.lastUsedTime());
                    return entry;
                })
                .toList());
        return credentials;
    }

    /**
     * 已授权应用(scope 用原始标识,便于机器处理)。
     *
     * @param overview 用户中心数据
     * @return 授权条目
     */
    private static List<Map<String, Object>> consents(AccountOverview overview) {
        return overview.consents().stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("clientKey", item.clientKey());
                    entry.put("clientName", item.clientName());
                    entry.put("scopes", item.scopes().stream()
                            .map(AccountOverview.ScopeItem::code)
                            .toList());
                    entry.put("grantedTime", item.grantedTime());
                    return entry;
                })
                .toList();
    }

    /**
     * 登录设备。
     *
     * @param overview 用户中心数据
     * @return 设备条目
     */
    private static List<Map<String, Object>> sessions(AccountOverview overview) {
        return overview.sessions().stream()
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("loginType", item.loginType());
                    entry.put("ip", item.ip());
                    entry.put("userAgent", item.userAgent());
                    entry.put("createdTime", item.createdTime());
                    entry.put("lastActiveTime", item.lastActiveTime());
                    entry.put("current", item.current());
                    return entry;
                })
                .toList();
    }

    /**
     * 最近登录事件。
     *
     * @param principal 当前登录主体
     * @return 事件条目,按发生时间倒序,最多 {@value #RECENT_EVENT_LIMIT} 条
     */
    private List<Map<String, Object>> loginEvents(IamUserPrincipal principal) {
        return loginEventManager.page(principal.getRealmId(), principal.getUsername(),
                        null, null, null, null, 1, RECENT_EVENT_LIMIT)
                .getRecords().stream()
                .map(this::eventEntry)
                .toList();
    }

    /**
     * 登录事件转导出条目。
     *
     * @param event 登录事件
     * @return 事件条目
     */
    private Map<String, Object> eventEntry(LoginEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("eventType", event.getEventType());
        entry.put("loginType", event.getLoginType());
        entry.put("ip", event.getIp());
        entry.put("userAgent", event.getUserAgent());
        entry.put("failReason", event.getFailReason());
        entry.put("occurredTime", event.getOccurredTime());
        return entry;
    }
}
