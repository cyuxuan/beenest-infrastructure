package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 客户端准入过滤器。
 * <p>挂在授权端点之前,请求携带 client_id 时按目标客户端的准入策略判定当前会话:
 * 不满足则签发挑战票据转向补验页,而非直接拒绝,联邦登录先建会话后知客户端,
 * 直接拒绝会让已登录用户被踢回登录页。</p>
 * <p>登录页的展示过滤只是体验优化,准入判定在过滤器内完成。</p>
 */
public class ClientAdmissionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientAdmissionFilter.class);

    /** 授权端点路径的后缀。 */
    private static final String AUTHORIZE_SUFFIX = "/oauth2/authorize";
    /** 挑战页路径。 */
    private static final String CHALLENGE_PAGE = "/login-challenge";

    private final RegisteredClientRepository registeredClientRepository;
    private final RealmProvider realmProvider;
    private final AdmissionChallengeService challengeService;
    private final MfaPendingService mfaPendingService;
    private final LoginEventPublisher loginEventPublisher;

    public ClientAdmissionFilter(RegisteredClientRepository registeredClientRepository,
                                  RealmProvider realmProvider,
                                  AdmissionChallengeService challengeService,
                                  MfaPendingService mfaPendingService,
                                  LoginEventPublisher loginEventPublisher) {
        this.registeredClientRepository = registeredClientRepository;
        this.realmProvider = realmProvider;
        this.challengeService = challengeService;
        this.mfaPendingService = mfaPendingService;
        this.loginEventPublisher = loginEventPublisher;
    }

    /**
     * 拦截授权端点请求,按目标客户端的准入策略判定当前会话。
     * <p>GET 与 POST 同等对待,直接 POST 授权参数不能绕过闸门;路径按 servletPath 匹配,
     * 部署在子路径下不受 requestURI 前缀影响。会话不存在或未登录时放行给登录页处理。
     * 判定需要补验时签发票据并跳转挑战页,需要二步验证时额外签发会话级 MFA 票据。
     * 配置损坏态(denyAll 且白名单为空且未要求 MFA)下补验无意义,直接返回 403 并留痕。</p>
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
        // 路径按 servletPath 匹配,部署在子路径下不受 requestURI 前缀影响
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
            // 客户端不存在由协议栈报错,此处不介入,不泄露是否存在
            filterChain.doFilter(request, response);
            return;
        }
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registeredClient);
        // denyAll 的白名单同样为空,必须显式排除,配置损坏态不能走快路径
        if (policy.allowedMethods().isEmpty() && !policy.requiresMfa() && !policy.denyAll()) {
            filterChain.doFilter(request, response);
            return;
        }
        var payload = IamRedisSecurityContextRepository.currentPayload(request);
        if (payload == null || payload.principal() == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String sessionLoginType = payload.principal().getLoginType();
        boolean mfaVerified = Boolean.TRUE.equals(payload.mfaVerified());
        AdmissionDecision decision = decide(policy, sessionLoginType, mfaVerified);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        // 票据挂在当前会话上,补验成功后升级同一会话
        RealmContext realmContext = resolveContext();
        if (realmContext == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String realmKey = realmContext.realmKey();
        String sid = IamRedisSecurityContextRepository.currentSessionId(request);
        // 不允许 + 未要求 MFA + 白名单为空 三点同时成立识别为配置损坏,补验解决不了
        if (!decision.requiresMfa() && decision.allowedLoginTypes().isEmpty()) {
            log.error("客户端 {} 的准入策略无法解析,拒绝授权请求: realm={}, sid={}", clientId, realmKey, sid);
            loginEventPublisher.publish(realmKey, payload.principal().getUserId(), payload.principal().getUsername(),
                    LoginEventEvent.CLIENT_ADMISSION_DENIED, sessionLoginType,
                    request.getRemoteAddr(), request.getHeader("User-Agent"),
                    "客户端准入配置损坏,无法判定");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "客户端准入配置异常,请联系管理员");
            return;
        }
        // 回跳地址:路径取 servletPath;参数由 getParameterMap 重新编码,POST 的授权参数
        // 在表单体里,getQueryString 为空,照抄会让回跳丢失 client_id 等必填项
        String returnTo = servletPath(request) + "?" + encodeQuery(request.getParameterMap());
        AdmissionChallenge challenge = challengeService.issue(
                realmKey, sid, clientId, policy, returnTo);
        loginEventPublisher.publish(realmKey, payload.principal().getUserId(),
                payload.principal().getUsername(),
                LoginEventEvent.CLIENT_ADMISSION_CHALLENGE, sessionLoginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                "需要补验:" + (decision.requiresMfa() ? "MFA" : String.join(",", decision.allowedLoginTypes())));
        // 判定需要二步验证时签发会话级 MFA 票据再跳挑战页,已登录用户不重走完整登录
        if (decision.requiresMfa()) {
            String mfaToken = mfaPendingService.issueForSession(
                    realmKey, payload.principal().getUserId(), sid, sessionLoginType);
            response.sendRedirect(CHALLENGE_PAGE + "?realm=" + realmKey
                    + "&token=" + challenge.challengeToken() + "&mfa=" + mfaToken);
            return;
        }
        response.sendRedirect(CHALLENGE_PAGE + "?realm=" + realmKey + "&token=" + challenge.challengeToken());
    }

    /**
     * 解析请求所属身份域,与客户端仓储同口径。
     * <p>全局协议端点不带域前缀时没有域上下文,客户端仓储按 default 域解析客户端;
     * 准入判定若不跟着回落,猜一条全局路径就能跳过客户端配置的登录方式与 MFA 要求。</p>
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
     * 取上下文内路径,与 RealmContextFilter 的取法一致,部署在子路径下也正确。
     *
     * @param request 当前请求
     * @return servletPath 为空时回退到 requestURI
     */
    private static String servletPath(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || path.isBlank() ? request.getRequestURI() : path;
    }

    /**
     * 把请求参数重新编码成查询串,跳过空值。
     *
     * @param params 请求参数表
     * @return 编码后的查询串;无有效参数时为空字符串
     */
    private static String encodeQuery(Map<String, String[]> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((name, values) -> {
            for (String value : values) {
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return sb.toString();
    }

    /**
     * 判定当前会话是否满足客户端的准入要求。
     * <p>denyAll 必须放在最前:白名单在 denyAll 下归一为空,先判空集合会把限制静默解除。
     * 会话登录方式未知按不命中处理,要求重新证明而非放行。</p>
     *
     * @param policy           客户端准入策略
     * @param sessionLoginType 会话当前登录方式码
     * @param mfaVerified      会话是否已完成二步验证
     * @return 判定结果,包含是否放行、要求的登录方式集合与是否要求 MFA
     */
    static AdmissionDecision decide(ClientAdmissionPolicy policy, String sessionLoginType,
                                    boolean mfaVerified) {
        // 配置损坏态直接拒绝,先于白名单空判定:denyAll 下白名单归一为空,
        // 若先判空集合会把限制静默解除
        if (policy.denyAll()) {
            return AdmissionDecision.deny();
        }
        // 第一因素:白名单不空时会话认证方式必须命中
        if (!policy.allowedMethods().isEmpty()) {
            LoginMethod method = LoginMethod.fromCode(sessionLoginType);
            if (method == null || !policy.allowedMethods().contains(method)) {
                Set<String> codes = new LinkedHashSet<>();
                policy.allowedMethods().forEach(m -> codes.add(m.code()));
                return AdmissionDecision.requireFactor(codes);
            }
        }
        // 二步验证:客户端要求且会话尚未完成
        if (policy.requiresMfa() && !mfaVerified) {
            return AdmissionDecision.requireMfa();
        }
        return AdmissionDecision.allow();
    }
}
