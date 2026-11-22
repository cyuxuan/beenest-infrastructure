package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 客户端应用授权闸门。
 * <p>挂在授权端点之前:客户端绑定了应用服务时,当前会话的用户必须对该应用可见,
 * 否则直接 403。绑定关系为空的客户端(平台自身、机器对机器)放行,登录前的请求
 * 也放行——登录完成后授权端点会再次经过本过滤器。</p>
 * <p>没有这道判定时,门户的可见性只挡住"从门户点进去",知道 client_id 与回调地址
 * 的人仍可直接走授权码流程拿到该应用的令牌。</p>
 */
public class ClientApplicationAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientApplicationAccessFilter.class);

    /** 授权端点路径的后缀。 */
    private static final String AUTHORIZE_SUFFIX = "/oauth2/authorize";

    /** 拒绝访问时写入请求的应用名称,错误页据此点名被拒绝的应用。 */
    public static final String DENIED_APP_NAME_ATTRIBUTE = "club.beenest.iam.error.deniedAppName";

    private final RegisteredClientRepository registeredClientRepository;
    private final RealmProvider realmProvider;
    private final ApplicationVisibilityService visibilityService;
    private final LoginEventPublisher loginEventPublisher;
    /** 是否启用闸门;关闭后回到"门户可见性只管展示"的旧行为 */
    private final boolean enabled;

    public ClientApplicationAccessFilter(RegisteredClientRepository registeredClientRepository,
                                         RealmProvider realmProvider,
                                         ApplicationVisibilityService visibilityService,
                                         LoginEventPublisher loginEventPublisher,
                                         boolean enabled) {
        this.registeredClientRepository = registeredClientRepository;
        this.realmProvider = realmProvider;
        this.visibilityService = visibilityService;
        this.loginEventPublisher = loginEventPublisher;
        this.enabled = enabled;
    }

    /**
     * 判定当前会话能否为请求的目标客户端签发授权码。
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 过滤器链
     * @throws ServletException 转发或响应写入抛出
     * @throws IOException      响应写出抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        // 路径按 servletPath 匹配,部署在子路径下不受 requestURI 前缀影响;
        // GET 与 POST 同等对待,直接 POST 授权参数同样要过闸门
        String method = request.getMethod();
        boolean authorizeRequest = ("GET".equals(method) || "POST".equals(method))
                && servletPath(request).endsWith(AUTHORIZE_SUFFIX);
        if (!authorizeRequest) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientId = request.getParameter("client_id");
        if (clientId == null || clientId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null) {
            // 客户端不存在由协议栈报错,此处不介入
            filterChain.doFilter(request, response);
            return;
        }
        RealmContext context = resolveContext();
        if (context == null) {
            // 域都解析不出时客户端仓储同样查不到客户端,交给协议栈报错
            filterChain.doFilter(request, response);
            return;
        }
        ApplicationService app = visibilityService.boundApplication(context.realmId(), registeredClient);
        if (app == null) {
            // 未绑定应用:平台自身客户端与机器对机器调用没有门户可见性概念
            filterChain.doFilter(request, response);
            return;
        }
        // 未登录:交给登录流程,登录后的授权请求会再次经过本过滤器。
        // 这里取 SecurityContext 而不是请求属性:上下文是延迟加载的,属性未必已被写入
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof IamUserPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (visibilityService.canAccess(principal, app)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.info("拒绝未授权应用的授权请求: realm={}, clientId={}, appCode={}, userId={}",
                context.realmKey(), clientId, app.getAppCode(), principal.getUserId());
        loginEventPublisher.publish(context.realmKey(), principal.getUserId(), principal.getUsername(),
                LoginEventEvent.APPLICATION_ACCESS_DENIED, principal.getLoginType(),
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                "账号无权访问应用: " + app.getAppCode());
        request.setAttribute(DENIED_APP_NAME_ATTRIBUTE, app.getAppName());
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "当前账号无权访问该应用");
    }

    /**
     * 解析请求所属身份域,与客户端仓储同口径。
     * <p>全局协议端点(/oauth2/authorize 不带域前缀)没有域上下文,客户端仓储按
     * default 域解析客户端;闸门若不跟着回落,猜一条全局路径就能绕过应用可见性。</p>
     *
     * @return 身份域上下文;上下文与 default 域都没有时返回 null
     */
    private RealmContext resolveContext() {
        RealmContext context = RealmContextHolder.get();
        if (context != null && context.realmId() != null) {
            return context;
        }
        RealmModel realm = realmProvider.findByRealmKey("default").orElse(null);
        return realm == null ? null : new RealmContext(realm.id(), realm.realmKey(), realm);
    }

    /**
     * 取上下文内路径,与 RealmContextFilter 的取法一致。
     *
     * @param request 当前请求
     * @return servletPath 为空时回退到 requestURI
     */
    private static String servletPath(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || path.isBlank() ? request.getRequestURI() : path;
    }
}
