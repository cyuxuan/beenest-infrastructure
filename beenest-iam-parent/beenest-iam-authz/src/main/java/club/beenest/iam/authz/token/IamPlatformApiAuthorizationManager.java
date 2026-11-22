package club.beenest.iam.authz.token;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * IAM 自身接口的令牌类别判定。
 * <p>管理端、用户自助端与应用门户是 IAM 自己的接口,只应由浏览器会话或平台类令牌调用。
 * 绑定到业务应用的客户端所签发的令牌,授权范围是该应用的权限命名空间,
 * 不该反过来调用 IAM 自身的管理接口——尤其是 {@code LEGACY_GLOBAL} 收敛档的令牌
 * 仍带用户权限全集,若不拦,一个业务应用的令牌即可直接读写用户、角色与客户端。</p>
 * <p>会话认证不在此处判定:会话主体能做什么由各端点的方法级权限码决定,
 * 这里只负责把"令牌类别"这一维挡在门外。</p>
 */
public class IamPlatformApiAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** 令牌类别声明名 */
    public static final String CLAIM_TOKEN_KIND = "token_kind";
    /** 平台第一方客户端:未绑定任何应用服务 */
    public static final String KIND_PLATFORM = "PLATFORM";
    /** 机器调用方:client_credentials 换取,主体是客户端绑定的服务账号 */
    public static final String KIND_MACHINE = "MACHINE";
    /** 业务应用客户端:令牌授权范围限于该应用 */
    public static final String KIND_APPLICATION = "APPLICATION";

    /**
     * 判定当前认证是否允许访问 IAM 自身接口。
     * <p>未认证返回拒绝,由异常转换环节给出 401;已认证但为业务应用令牌返回拒绝,给出 403。</p>
     * <p>不按 {@code isAuthenticated()} 判定:匿名主体与真实主体之外的中间态在实现上
     * 并不稳定,按"主体类型"判定更可靠——会话主体是用户快照,令牌主体是 JWT。</p>
     *
     * @param authentication 当前认证提供者
     * @param context        请求上下文
     * @return 授权决策
     */
    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                          RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null || current instanceof AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        if (current instanceof JwtAuthenticationToken jwtAuthentication) {
            String kind = jwtAuthentication.getToken().getClaimAsString(CLAIM_TOKEN_KIND);
            boolean allowed = KIND_PLATFORM.equals(kind) || KIND_MACHINE.equals(kind);
            return new AuthorizationDecision(allowed);
        }
        return new AuthorizationDecision(current.getPrincipal() instanceof IamUserPrincipal);
    }
}
