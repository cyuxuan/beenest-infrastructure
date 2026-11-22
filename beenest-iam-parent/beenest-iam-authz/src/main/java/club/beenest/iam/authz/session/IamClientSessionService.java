package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 客户端会话服务。
 * <p>记录会话与客户端的登录关联,并把会话标识按授权 id 暂存,供签发 ID Token 时取回 sid。</p>
 * <p>写入时机在授权码保存(授权端点的浏览器请求携带会话 Cookie),而非令牌交换
 * (应用后端请求通常不携带会话 Cookie,取不到 sid)。</p>
 */
@Service
public class IamClientSessionService {

    private static final Logger log = LoggerFactory.getLogger(IamClientSessionService.class);

    /** 会话标识暂存时长:覆盖 refresh token 的最长生命期即可 */
    private static final Duration SID_STASH_TTL = Duration.ofHours(12);

    private final ClientSessionStore clientSessionStore;
    private final UserSessionStore userSessionStore;
    private final RedisUtils redisUtils;

    public IamClientSessionService(ClientSessionStore clientSessionStore,
                                   UserSessionStore userSessionStore,
                                   RedisUtils redisUtils) {
        this.clientSessionStore = clientSessionStore;
        this.userSessionStore = userSessionStore;
        this.redisUtils = redisUtils;
    }

    /**
     * 记录当前浏览器会话在指定客户端上的登录,并把会话标识与该授权绑定。
     * <p>无浏览器上下文(如后端直连换令牌)时静默跳过:这类调用没有 IAM 会话,
     * 也就没有需要广播的登出对象。</p>
     *
     * @param principal       登录主体
     * @param clientDbId      客户端主键(iam_client.id,即 RegisteredClient 的 id)
     * @param authorizationId 授权 id(ID Token 签发时按此取回会话标识)
     */
    public void recordCurrentSession(IamUserPrincipal principal, Long clientDbId, String authorizationId) {
        if (principal == null || clientDbId == null) {
            return;
        }
        // 取当前请求的会话标识(来自会话 Cookie)
        String sid = currentSessionId();
        if (sid == null || sid.isBlank()) {
            return;
        }
        // 会话记录必须存在(登录成功时已落库),取其主键作为归属
        UserSessionModel session = userSessionStore.findBySid(sid);
        if (session == null || !principal.getRealmId().equals(session.realmId())) {
            return;
        }
        // 落库幂等
        try {
            clientSessionStore.record(principal.getRealmId(), session.id(), principal.getUserId(), clientDbId);
            log.debug("记录客户端会话: sid={}, userId={}, client={}", sid, principal.getUserId(), clientDbId);
        } catch (RuntimeException e) {
            // 记录失败不阻断授权:登出时仅少一次该应用的广播,授权仍受令牌校验保护
            log.warn("客户端会话写入失败: sid={}, client={}", sid, clientDbId, e);
        }
        // 暂存会话标识:令牌端点在另一个请求中签发 ID Token,只能按授权 id 读取
        if (authorizationId != null && !authorizationId.isBlank()) {
            redisUtils.set(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_SID, authorizationId),
                    sid, SID_STASH_TTL);
        }
    }

    /**
     * 取回授权对应的会话标识(授权码流签发 ID Token 时使用)。
     *
     * @param authorizationId 授权 id
     * @return 会话标识;不存在或已过期返回 null
     */
    public String resolveSessionId(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            return null;
        }
        return redisUtils.get(String.format(IamRedisKeyConstants.OAUTH2_AUTHORIZATION_SID, authorizationId));
    }

    /**
     * 取当前请求的会话标识:来自会话 Cookie,与 iam_user_session.sid 一致。
     * <p>容器会话 id 只存在于创建它的进程,多实例部署时授权请求可能取不到,
     * 后通道登出广播会漏掉应用,故不用容器会话 id。</p>
     *
     * @return 会话标识;无请求上下文时返回 null
     */
    private static String currentSessionId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return IamSessionCookie.read(servletAttributes.getRequest());
        }
        return null;
    }
}
