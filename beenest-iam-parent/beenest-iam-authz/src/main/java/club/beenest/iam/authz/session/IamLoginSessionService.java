package club.beenest.iam.authz.session;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.common.event.LoginEventEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 登录态建立服务。
 * <p>账号口令登录、REST 登录、联邦登录回调共用同一流程:签发会话标识、登记会话记录、
 * 写入登录事件、把登录态落到共享会话存储。</p>
 */
@Service
public class IamLoginSessionService {

    private static final Logger log = LoggerFactory.getLogger(IamLoginSessionService.class);

    private final IamUserSessionService userSessionService;
    private final AdmissionChallengeService challengeService;
    private final LoginEventPublisher loginEventPublisher;
    private final SecurityContextRepository securityContextRepository;
    /** 会话 Cookie 是否只走 HTTPS */
    private final boolean cookieSecure;

    public IamLoginSessionService(IamUserSessionService userSessionService,
                                  AdmissionChallengeService challengeService,
                                  LoginEventPublisher loginEventPublisher,
                                  SecurityContextRepository securityContextRepository,
                                  @Value("${iam.session.cookie-secure:true}") boolean cookieSecure) {
        this.userSessionService = userSessionService;
        this.challengeService = challengeService;
        this.loginEventPublisher = loginEventPublisher;
        this.securityContextRepository = securityContextRepository;
        this.cookieSecure = cookieSecure;
    }

    /**
     * 建立登录态。
     * <p>表单登录、REST 登录、联邦回调的统一入口;依据当前会话是否存在待处理的
     * 准入挑战,决定升级原会话还是新建会话。</p>
     *
     * @param request   当前请求
     * @param response  当前响应
     * @param principal 登录主体
     * @param loginType 本次认证方式(LoginMethod 存储码)
     * @return true 表示走了升级路径(补验成功并升级了原会话),false 表示新建了会话
     */
    public boolean establish(HttpServletRequest request, HttpServletResponse response,
                             IamUserPrincipal principal, String loginType) {
        return establish(request, response, principal, loginType, null);
    }

    /**
     * 建立登录态(可携带挑战令牌)。
     * <p>挑战令牌用于跨站联邦回跳:平台会话 Cookie 是 SameSite=Lax,
     * SAML 走 HTTP-POST 绑定回跳时是跨站 POST,浏览器不带 Cookie,
     * 此时只能靠随回跳带回来的令牌定位挑战。</p>
     *
     * @param request        当前请求
     * @param response       当前响应
     * @param principal      登录主体
     * @param loginType      本次认证方式(LoginMethod 存储码)
     * @param challengeToken 挑战令牌;可为空
     * @return true 表示走了升级路径,false 表示新建了会话
     */
    public boolean establish(HttpServletRequest request, HttpServletResponse response,
                             IamUserPrincipal principal, String loginType, String challengeToken) {
        // 域键取自主体而非 RealmContextHolder:联邦回跳等路径的线程上下文未必就绪,
        // 主体里的域键一定是本次登录的域
        String realmKey = principal.getRealmKey();
        String sid = IamSessionCookie.read(request);
        AdmissionChallenge challenge = challengeService.resolve(realmKey, challengeToken, sid);
        if (challenge != null && challenge.allows(loginType)) {
            // 先取会话快照校验归属,消费票据前确认升级对象与票据一致
            IamSessionPayload payload = userSessionService.loadSession(challenge.sid());
            if (payload == null || payload.principal() == null) {
                // 会话已不存在:消费孤儿票据,但返回 false 按普通登录处理,
                // 避免调用方误判升级成功并跳回授权端点
                challengeService.consume(realmKey, challenge);
                log.warn("准入补验的会话已不存在,按普通登录处理: realm={}, sid={}",
                        realmKey, challenge.sid());
            } else if (!Objects.equals(payload.principal().getUserId(), principal.getUserId())) {
                // 票据属于其他用户的会话:不消费,留给票据对应的会话
                log.warn("准入补验的主体与会话不一致,忽略票据: realm={}, sid={}, sessionUser={}, loginUser={}",
                        realmKey, challenge.sid(), payload.principal().getUserId(), principal.getUserId());
            } else if (userSessionService.upgradeAuthentication(
                    challenge.sid(), loginType, payload.mfaVerified())) {
                // 升级成功后再消费票据;升级失败时票据保留,可再次补验
                challengeService.consume(realmKey, challenge);
                loginEventPublisher.publish(realmKey, principal.getUserId(), principal.getUsername(),
                        LoginEventEvent.LOGIN_SUCCESS, loginType,
                        request.getRemoteAddr(), request.getHeader("User-Agent"), null);
                return true;
            } else {
                // load 之后会话被并发删除:按普通登录处理
                log.warn("准入补验升级时会话已消失: realm={}, sid={}", realmKey, challenge.sid());
            }
        }
        // 无挑战或认证方式不命中:按普通登录处理;方式不命中时票据保留,
        // 用户后续访问受限应用仍可补验
        // 会话 Cookie 带有效期,关掉浏览器并不会结束服务端会话;
        // 同一账号再次登录时先收尾旧会话,否则会同时挂着两条活跃会话
        endPreviousSession(sid, principal);
        String newSid = userSessionService.startSession(principal, loginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        request.setAttribute(IamRedisSecurityContextRepository.SESSION_ID_ATTRIBUTE, newSid);
        IamSessionCookie.write(response, newSid, cookieSecure, Duration.ofSeconds(maxLifetimeSeconds()));
        loginEventPublisher.publish(realmKey, principal.getUserId(), principal.getUsername(),
                LoginEventEvent.LOGIN_SUCCESS, loginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(toAuthentication(principal));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return false;
    }

    /**
     * 结束浏览器残留的同一账号旧会话。
     * <p>只处理同一账号:共用设备上残留的是别人的会话,新登录会覆盖浏览器里的登录态,
     * 不应顺手把别人的会话踢下线。</p>
     *
     * @param sid       请求携带的会话标识;可为空
     * @param principal 本次登录主体
     */
    private void endPreviousSession(String sid, IamUserPrincipal principal) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        IamSessionPayload previous = userSessionService.loadSession(sid);
        if (previous == null || previous.principal() == null) {
            return;
        }
        if (!Objects.equals(previous.principal().getUserId(), principal.getUserId())) {
            return;
        }
        userSessionService.replaceSession(sid);
        log.info("同账号重新登录,已结束旧会话: realm={}, userId={}",
                principal.getRealmKey(), principal.getUserId());
    }

    /**
     * 域策略下的会话最大存续时长(秒),Cookie 有效期与之对齐。
     *
     * @return 最大存续秒数;无域上下文时按平台兜底值
     */
    private static long maxLifetimeSeconds() {
        RealmContext context = RealmContextHolder.get();
        return IamUserSessionService.resolveMaxSecondsOf(context == null ? null : context.realm());
    }

    /**
     * 由用户主体构建已认证的 Authentication(权限码即 authority)。
     *
     * @param principal 登录主体
     * @return 已认证的 Authentication
     */
    private static Authentication toAuthentication(IamUserPrincipal principal) {
        List<GrantedAuthority> authorities = principal.getAuthorities().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
}
