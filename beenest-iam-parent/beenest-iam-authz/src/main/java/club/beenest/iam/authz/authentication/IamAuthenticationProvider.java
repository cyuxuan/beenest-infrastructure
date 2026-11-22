package club.beenest.iam.authz.authentication;

import club.beenest.iam.authz.authentication.step.AuthenticationContext;
import club.beenest.iam.authz.authentication.step.AuthenticationStepChain;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 账号密码认证 Provider。
 * <p>表单登录与 REST 登录共用;域取自当前请求上下文,无上下文时回落默认域。
 * 认证通过后以 {@link club.beenest.iam.authz.principal.IamUserPrincipal} 为主体,
 * 权限码作为 GrantedAuthority。</p>
 * <p>本类只负责"组装上下文 → 执行步骤链 → 构建主体",具体的校验逻辑全部在
 * {@link AuthenticationStepChain} 的步骤里,新增校验或登录方式不需要改动本类。</p>
 */
public class IamAuthenticationProvider implements AuthenticationProvider {

    /** 认证步骤链:域解析、锁定检查、验证码、凭据、状态、计数清理、二步验证门禁 */
    private final AuthenticationStepChain stepChain;
    /** 主体工厂:登录与会话权限刷新共用同一套构建口径 */
    private final IamPrincipalFactory principalFactory;

    public IamAuthenticationProvider(AuthenticationStepChain stepChain, IamPrincipalFactory principalFactory) {
        this.stepChain = stepChain;
        this.principalFactory = principalFactory;
    }

    /**
     * 账号密码认证。
     * <p>把登录名与口令装进上下文后交给步骤链,任一步骤抛异常即终止并原样上抛;
     * 链执行完毕即表示认证通过,此时上下文里已有用户、登录方式与身份域。</p>
     *
     * @param authentication 携带登录名与口令的未认证令牌
     * @return 认证通过的 {@link club.beenest.iam.authz.principal.IamUserPrincipal} 认证结果
     * @throws AuthenticationException 域不可用、凭据错误、账号禁用、需二步验证等
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = String.valueOf(authentication.getPrincipal());
        String password = String.valueOf(authentication.getCredentials());
        AuthenticationContext context = new AuthenticationContext(username, password, currentRequest());
        stepChain.execute(context);
        return buildAuthentication(context.getRealm(), context.getUser(), context.getLoginType());
    }

    /**
     * 按指定认证方式构建已认证主体。
     *
     * @param realm     所属身份域
     * @param user      用户
     * @param loginType 本次认证方式(LoginMethod 存储码);未知传 null
     * @return 已认证的 Authentication 对象
     */
    public Authentication buildAuthentication(RealmModel realm, UserModel user, String loginType) {
        // 主体统一由工厂构建:登录时算一次、会话权限刷新时算一次,口径必须一致
        return principalFactory.toAuthentication(principalFactory.build(realm, user, loginType));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /**
     * 读取当前线程绑定的 HTTP 请求。
     *
     * @return 当前请求;无请求上下文(如异步调用)时返回 null
     */
    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
