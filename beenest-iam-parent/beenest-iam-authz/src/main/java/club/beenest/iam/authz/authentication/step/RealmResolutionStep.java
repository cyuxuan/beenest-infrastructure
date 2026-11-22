package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

/**
 * 身份域解析步骤。
 * <p>确定本次认证属于哪个身份域并加载该域的登录策略,后续所有步骤都依赖这两个值。
 * 域优先取自请求上下文,无上下文时回落 {@code default} 域。</p>
 */
public class RealmResolutionStep implements AuthenticationStep {

    /** 无请求上下文时回落的身份域标识 */
    private static final String DEFAULT_REALM_KEY = "default";

    private final RealmProvider realmProvider;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final LoginFailureRecorder failureRecorder;

    public RealmResolutionStep(RealmProvider realmProvider, IamSecurityPolicyManager securityPolicyManager,
                               LoginFailureRecorder failureRecorder) {
        this.realmProvider = realmProvider;
        this.securityPolicyManager = securityPolicyManager;
        this.failureRecorder = failureRecorder;
    }

    @Override
    public Stage stage() {
        return Stage.PRE_CHECK;
    }

    @Override
    public String name() {
        return "realm-resolution";
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 解析身份域并加载登录策略。
     *
     * @param context 认证上下文,执行后写入身份域与登录策略
     * @throws AuthenticationException 身份域不存在或未启用时抛出
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        RealmModel realm = currentRealm();
        if (realm == null || !realm.enabled()) {
            failureRecorder.record(context, null, "身份域不存在或未启用");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        context.setRealm(realm);
        // 登录策略带缓存;域不存在时不会走到这里,因此这里必然非 null 域的策略
        LoginPolicy loginPolicy = securityPolicyManager.getLoginPolicy(realm.id(), realm.realmKey());
        context.setLoginPolicy(loginPolicy);
    }

    /**
     * 取当前请求上下文所属身份域。
     *
     * @return 上下文中的身份域;无上下文时返回 default 域,该域也不存在时返回 null
     */
    private RealmModel currentRealm() {
        RealmContext context = RealmContextHolder.get();
        if (context != null) {
            return context.realm();
        }
        return realmProvider.findByRealmKey(DEFAULT_REALM_KEY).orElse(null);
    }
}
