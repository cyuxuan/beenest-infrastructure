package club.beenest.iam.authz.account;

import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.authorization.RedisOAuth2AuthorizationConsentService;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.webauthn.WebAuthnCredentialService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户中心数据装配服务。
 * <p>只读取当前登录用户自己的数据,越权由 {@link CurrentUserHolder} 提供的主体主键约束,
 * 不使用管理端权限码。</p>
 * <p>授权记录存放在 Redis,客户端名与 scope 展示名在这里补齐:
 * 客户端已被删除时名称为 null(页面回落显示 clientKey),scope 无对应文案时回落显示原始标识。</p>
 */
@Service
public class AccountOverviewService {

    /** scope 展示名的资源键前缀 */
    private static final String SCOPE_LABEL_KEY = "page.consent.scope.%s.label";

    private final IamUserManager userManager;
    private final UserProvider userProvider;
    private final MfaService mfaService;
    private final WebAuthnCredentialService webAuthnCredentialService;
    private final RedisOAuth2AuthorizationConsentService consentService;
    private final ApplicationAccessRevocationService revocationService;
    private final ClientProvider clientProvider;
    private final IamUserSessionService userSessionService;
    private final IamLogoutService logoutService;
    private final RequiredActionService requiredActionService;
    private final MessageSource messageSource;

    public AccountOverviewService(IamUserManager userManager, UserProvider userProvider,
                                  MfaService mfaService, WebAuthnCredentialService webAuthnCredentialService,
                                  RedisOAuth2AuthorizationConsentService consentService,
                                  ApplicationAccessRevocationService revocationService,
                                  ClientProvider clientProvider, IamUserSessionService userSessionService,
                                  IamLogoutService logoutService,
                                  RequiredActionService requiredActionService, MessageSource messageSource) {
        this.userManager = userManager;
        this.userProvider = userProvider;
        this.mfaService = mfaService;
        this.webAuthnCredentialService = webAuthnCredentialService;
        this.consentService = consentService;
        this.revocationService = revocationService;
        this.clientProvider = clientProvider;
        this.userSessionService = userSessionService;
        this.logoutService = logoutService;
        this.requiredActionService = requiredActionService;
        this.messageSource = messageSource;
    }

    /**
     * 装配当前登录用户的用户中心数据。
     *
     * @param currentSid 当前请求所用会话标识,用于在设备列表中标记"本机";可为 null
     * @return 用户中心数据
     * @throws BusinessException 当前无登录主体,或用户记录已不存在
     */
    public AccountOverview overview(String currentSid) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        User user = userManager.getById(principal.getUserId());
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + principal.getUserId());
        }
        return new AccountOverview(
                profile(user, principal),
                credentials(principal),
                consents(principal),
                sessions(principal, currentSid),
                pendingActions(principal));
    }

    /**
     * 撤销本人对某个客户端的授权。
     * <p>客户端必须属于当前域,否则按"记录不存在"处理,不泄露跨域客户端是否存在。</p>
     *
     * @param clientKey 客户端标识
     * @throws BusinessException 客户端不存在、不属于当前域,或本人未授权过该客户端
     */
    public void revokeConsent(String clientKey) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        ClientModel client = clientProvider.findByClientKey(principal.getRealmId(), clientKey)
                .filter(item -> principal.getRealmId().equals(item.realmId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在: " + clientKey));
        if (!consentService.revoke(String.valueOf(client.id()), principal.getUsername())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "授权记录不存在: " + clientKey);
        }
        // 同意记录只是授权端点的入口;已签发的令牌要一并收回,撤销才对第三方生效
        revocationService.revokeClientGrant(principal.getRealmId(), principal.getUserId(), client.id());
    }

    /**
     * 注销本人指定的登录设备。
     *
     * @param sid        目标会话标识
     * @param currentSid 当前请求所用会话标识
     * @throws BusinessException 会话不存在、不属于本人,或目标为当前会话
     */
    public void revokeSession(String sid, String currentSid) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        UserSessionModel session = userSessionService.findBySid(sid);
        if (session == null || !principal.getUserId().equals(session.userId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sid);
        }
        // 当前会话请走注销接口,避免注销成功后当前请求处于半失效状态
        if (sid.equals(currentSid)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "当前设备请使用退出登录");
        }
        logoutService.logout(sid);
    }

    /**
     * 组装账号资料。
     *
     * @param user      用户实体
     * @param principal 当前登录主体
     * @return 账号资料
     */
    private static AccountOverview.Profile profile(User user, IamUserPrincipal principal) {
        return new AccountOverview.Profile(
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getPhone(),
                Boolean.TRUE.equals(user.getPhoneVerified()),
                principal.getRealmKey(),
                user.getUserType());
    }

    /**
     * 组装登录凭证概况。
     *
     * @param principal 当前登录主体
     * @return 凭证概况
     */
    private AccountOverview.Credentials credentials(IamUserPrincipal principal) {
        MfaService.MfaStatus mfa = mfaService.status(principal.getUserId());
        return new AccountOverview.Credentials(
                userProvider.hasLocalPassword(principal.getUserId()),
                mfa.totpEnabled(),
                mfa.recoveryCodesRemaining(),
                webAuthnCredentialService.isAvailable(),
                webAuthnCredentialService.list(principal.getRealmId(), principal.getUserId()));
    }

    /**
     * 组装已授权应用列表。
     *
     * @param principal 当前登录主体
     * @return 授权记录,按授予时间倒序;无记录时返回空列表
     */
    private List<AccountOverview.ConsentItem> consents(IamUserPrincipal principal) {
        Map<String, Set<String>> granted = consentService.findByPrincipal(principal.getUsername());
        List<AccountOverview.ConsentItem> items = new ArrayList<>();
        granted.forEach((registeredClientId, authorities) -> {
            // 1. 客户端必须仍在当前域内,跨域与已删除客户端一律不展示
            ClientModel client = findClientInRealm(registeredClientId, principal.getRealmId());
            if (client == null) {
                return;
            }
            // 2. scope 按固定顺序展示,避免每次刷新顺序不同
            List<AccountOverview.ScopeItem> scopes = authorities.stream()
                    .sorted()
                    .map(this::scopeItem)
                    .toList();
            items.add(new AccountOverview.ConsentItem(
                    client.clientKey(),
                    client.clientName(),
                    scopes,
                    toLocalDateTime(consentService.grantedAt(registeredClientId, principal.getUsername()))));
        });
        items.sort(Comparator.comparing(AccountOverview.ConsentItem::grantedTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    /**
     * 组装登录设备列表。
     *
     * @param principal  当前登录主体
     * @param currentSid 当前请求所用会话标识
     * @return 设备列表,按登录时间倒序;无记录时返回空列表
     */
    private List<AccountOverview.SessionItem> sessions(IamUserPrincipal principal, String currentSid) {
        return userSessionService.listSessions(principal.getRealmId(), principal.getUserId()).stream()
                .map(session -> new AccountOverview.SessionItem(
                        session.sid(),
                        session.loginType(),
                        session.ip(),
                        session.userAgent(),
                        session.createdTime(),
                        session.lastActiveTime(),
                        session.sid().equals(currentSid)))
                .toList();
    }

    /**
     * 读取未完成的账户待办。
     *
     * @param principal 当前登录主体
     * @return 待办动作代码,按枚举声明顺序;无待办时返回空列表
     */
    private List<String> pendingActions(IamUserPrincipal principal) {
        Set<RequiredAction> pending = requiredActionService.pending(principal.getUserId());
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return pending.stream().map(Enum::name).toList();
    }

    /**
     * 按注册客户端主键查当前域客户端。
     *
     * @param registeredClientId 注册客户端主键字符串
     * @param realmId            身份域主键
     * @return 客户端;主键非法、不存在或跨域时为 null
     */
    private ClientModel findClientInRealm(String registeredClientId, Long realmId) {
        try {
            return clientProvider.findById(Long.valueOf(registeredClientId))
                    .filter(client -> realmId.equals(client.realmId()))
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把 scope 标识包装成带展示名的条目。
     *
     * @param code scope 原始标识
     * @return scope 条目;无对应文案时展示名与标识相同
     */
    private AccountOverview.ScopeItem scopeItem(String code) {
        String label = messageSource.getMessage(String.format(SCOPE_LABEL_KEY, code), null, code,
                LocaleContextHolder.getLocale());
        return new AccountOverview.ScopeItem(code, label);
    }

    /**
     * epoch 毫秒转本地时间。
     *
     * @param epochMillis epoch 毫秒
     * @return 本地时间;入参为 null 时返回 null
     */
    private static LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
