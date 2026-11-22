package club.beenest.iam.authz.web;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.List;

/**
 * 会话态的安全上下文仓库:登录态存在 Redis,Cookie 只携带不可推导的会话标识。
 * <p>存取的是平台自己的主体对象,读出后重建 Spring 的 Authentication,不序列化框架内部类型。
 * 会话签发只由 {@link club.beenest.iam.authz.session.IamLoginSessionService} 完成,本仓库不补建会话。</p>
 */
public class IamRedisSecurityContextRepository implements SecurityContextRepository {

    /** 请求属性:本次请求的会话标识(登录时写入,供后续逻辑取用) */
    public static final String SESSION_ID_ATTRIBUTE = "club.beenest.iam.SESSION_ID";
    /** 请求属性:本次请求读到的会话快照(同一请求内只读一次 Redis) */
    public static final String SESSION_PAYLOAD_ATTRIBUTE = "club.beenest.iam.SESSION_PAYLOAD";

    private final IamUserSessionService userSessionService;

    public IamRedisSecurityContextRepository(IamUserSessionService userSessionService) {
        this.userSessionService = userSessionService;
    }

    /**
     * 读取会话快照并还原登录态。
     *
     * @param holder 请求与响应持有器
     * @return 安全上下文;无会话或会话不存在时返回空上下文
     */
    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        HttpServletRequest request = holder.getRequest();
        // 1. 会话标识:优先取本次请求已解析出的,其次取 Cookie
        String sid = currentSessionId(request);
        if (sid == null) {
            return SecurityContextHolder.createEmptyContext();
        }
        // 2. 读快照:读不出来即视为会话不存在(过期、已被注销、快照损坏)
        IamSessionPayload payload = userSessionService.loadSession(sid);
        if (payload == null || payload.principal() == null) {
            return SecurityContextHolder.createEmptyContext();
        }
        request.setAttribute(SESSION_PAYLOAD_ATTRIBUTE, payload);
        // 3. 还原登录态
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(toAuthentication(payload.principal()));
        return context;
    }

    /**
     * 保存登录态:会话快照已在 Redis,不落任何数据,快照刷新由会话校验过滤器负责。
     * <p>表单登录的认证过滤器在成功处理器之前调用本方法,会话此时尚未签发。
     * 补建会话会让一次登录落两条会话,其中一条没有登录事件,Cookie 也被随后签发的会话覆盖。</p>
     *
     * @param context  安全上下文
     * @param request  当前请求
     * @param response 当前响应
     */
    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    }

    /**
     * 请求是否携带会话标识。
     *
     * @param request 当前请求
     * @return 存在会话标识时为 true
     */
    @Override
    public boolean containsContext(HttpServletRequest request) {
        return currentSessionId(request) != null;
    }

    /**
     * 本次请求的会话标识。
     *
     * @param request 当前请求
     * @return 会话标识;请求属性与 Cookie 均没有时为 null
     */
    public static String currentSessionId(HttpServletRequest request) {
        Object attribute = request.getAttribute(SESSION_ID_ATTRIBUTE);
        if (attribute instanceof String sid && !sid.isBlank()) {
            return sid;
        }
        return IamSessionCookie.read(request);
    }

    /**
     * 本次请求已读到的会话快照。
     *
     * @param request 当前请求
     * @return 会话快照;本次请求尚未读取时为 null
     */
    public static IamSessionPayload currentPayload(HttpServletRequest request) {
        Object attribute = request.getAttribute(SESSION_PAYLOAD_ATTRIBUTE);
        return attribute instanceof IamSessionPayload payload ? payload : null;
    }

    /**
     * 由用户主体构建已认证的 Authentication。
     *
     * @param principal 用户主体
     * @return 已认证的 Authentication,权限码即 authority
     */
    private static Authentication toAuthentication(IamUserPrincipal principal) {
        List<GrantedAuthority> authorities = principal.getAuthorities().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
}
