package club.beenest.iam.authz.logout;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.core.model.UserSessionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 统一登出入口。
 * <p>登出分四步:先取主体(取后即删快照),标记会话吊销,向应用广播登出,最后结束本地会话。
 * 顺序固定:先删快照会丢失 realm 与 sub,广播通知将无法定位下线用户。</p>
 * <p>浏览器注销、REST 注销、全端下线、OIDC RP-Initiated Logout、管理端强制下线、
 * 自助端注销设备等全部登出路径均经此入口;绕过它的路径上应用不会收到登出通知,
 * 该会话签出的访问令牌也不会被标记失效。</p>
 */
@Service
public class IamLogoutService {

    private static final Logger log = LoggerFactory.getLogger(IamLogoutService.class);

    private final IamUserSessionService userSessionService;
    private final IamLogoutBroadcastService broadcastService;
    private final SessionRevocationService revocationService;

    public IamLogoutService(IamUserSessionService userSessionService,
                            IamLogoutBroadcastService broadcastService,
                            SessionRevocationService revocationService) {
        this.userSessionService = userSessionService;
        this.broadcastService = broadcastService;
        this.revocationService = revocationService;
    }

    /**
     * 结束一个会话并广播登出。
     *
     * @param sid 会话标识;为空时直接返回(会话可能已被容器失效)
     */
    public void logout(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        // 1. 先取主体与域:会话快照删除后这两项就取不到了
        IamSessionPayload payload = userSessionService.loadSession(sid);
        IamUserPrincipal principal = payload == null ? null : payload.principal();
        UserSessionModel session = userSessionService.findBySid(sid);
        Long realmId = principal != null ? principal.getRealmId()
                : (session == null ? null : session.realmId());
        String realmKey = principal != null ? principal.getRealmKey() : currentRealmKey();
        // 2. 标记会话吊销:访问令牌带 sid 声明,验签环节据此拒绝该会话签出的令牌
        revocationService.revoke(sid, payload == null ? null : Duration.ofSeconds(payload.maxLifetimeSeconds()));
        // 3. 广播:失败不阻断退出(内部已按应用逐个吞异常并留痕)
        //    sub 由广播服务按每个应用各自的策略解析,这里只传主体
        if (realmId != null && realmKey != null) {
            try {
                int targets = broadcastService.broadcast(realmId, realmKey, sid, principal);
                if (targets > 0) {
                    log.info("单点登出广播完成: sid={}, 目标应用={}", sid, targets);
                } else {
                    log.debug("会话无接入应用记录,无需广播: sid={}", sid);
                }
            } catch (RuntimeException e) {
                log.warn("单点登出广播异常,继续结束本地会话: sid={}", sid, e);
            }
        }
        // 4. 结束本地会话:Redis 快照删除 + 记录置为已注销
        userSessionService.endSession(sid);
    }

    /**
     * 结束用户的全部活跃会话,逐个广播登出。
     * <p>全端下线、管理端强制下线、SAML 单点登出共用:逐个会话走同一套吊销与广播,
     * 避免"会话没了但应用还留着登录态"。</p>
     *
     * @param realmId 域主键
     * @param userId  用户主键
     * @return 被结束的会话数量
     */
    public int logoutAll(Long realmId, Long userId) {
        if (realmId == null || userId == null) {
            return 0;
        }
        List<UserSessionModel> sessions = userSessionService.listSessions(realmId, userId);
        int count = 0;
        for (UserSessionModel session : sessions) {
            if (!UserSessionModel.STATE_ACTIVE.equals(session.state())) {
                continue;
            }
            logout(session.sid());
            count++;
        }
        return count;
    }

    /**
     * 无主体可用时取当前请求的域标识。
     *
     * @return 域标识;无请求上下文时返回 default
     */
    private static String currentRealmKey() {
        RealmContext context = RealmContextHolder.get();
        return context == null ? "default" : context.realmKey();
    }
}
