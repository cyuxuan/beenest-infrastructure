package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.logout.IamLogoutBroadcastService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.ClientSessionModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 应用访问收回。
 * <p>门户可见性只在授权端点判定一次;用户直授被移除后,已签发的令牌与已建立的
 * 应用会话不会自己失效,形成"后台已收回、应用还能用"的窗口。本服务在授权变更
 * 提交后主动收回:吊销该用户在该客户端上的授权记录(访问令牌进黑名单、刷新令牌
 * 因记录删除失效)、定向登出该应用并关闭客户端会话。</p>
 * <p>只处理授权式可见性,且只收回指定用户与指定客户端:用户仍可通过角色或组
 * 进入时不动;其它应用的会话与授权一律不碰。角色/组授权的移除涉及全量成员展开,
 * 由刷新令牌闸门兜底,不在此处做批量踢下线。</p>
 */
@Service
public class ApplicationAccessRevocationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAccessRevocationService.class);

    /** 可见性:全员可见 */
    private static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 可见性:按权限码 */
    private static final String VISIBILITY_PERMISSION = "PERMISSION";

    private final IamApplicationServiceManager appServiceManager;
    private final ApplicationVisibilityService visibilityService;
    private final RedisOAuth2AuthorizationService authorizationService;
    private final ClientSessionStore clientSessionStore;
    private final IamLogoutBroadcastService broadcastService;
    private final RealmProvider realmProvider;

    public ApplicationAccessRevocationService(IamApplicationServiceManager appServiceManager,
                                              ApplicationVisibilityService visibilityService,
                                              RedisOAuth2AuthorizationService authorizationService,
                                              ClientSessionStore clientSessionStore,
                                              IamLogoutBroadcastService broadcastService,
                                              RealmProvider realmProvider) {
        this.appServiceManager = appServiceManager;
        this.visibilityService = visibilityService;
        this.authorizationService = authorizationService;
        this.clientSessionStore = clientSessionStore;
        this.broadcastService = broadcastService;
        this.realmProvider = realmProvider;
    }

    /**
     * 在授权变更提交后收回一批用户对某个应用的访问。
     * <p>必须等事务提交后再动:回滚的变更不能导致用户被踢下线;提前吊销还会让
     * 提交前的授权关系仍可被读到,判定结果与最终状态相反。</p>
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用服务主键
     * @param userIds      本次被移除授权的用户主键
     */
    public void revokeOnGrantRemoved(Long realmId, Long appServiceId, Collection<Long> userIds) {
        if (realmId == null || appServiceId == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Long> targets = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (targets.isEmpty()) {
            return;
        }
        Runnable task = () -> targets.forEach(userId -> revoke(realmId, appServiceId, userId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /**
     * 撤回授权同意后收回该用户在该客户端上的访问。
     * <p>同意只在授权端点被读取,令牌端点不查同意;只删同意记录的话,
     * 已签发的访问令牌与刷新令牌仍可继续使用,"撤销授权"对第三方不生效。</p>
     *
     * @param realmId  身份域主键
     * @param userId   用户主键
     * @param clientId 客户端主键
     */
    public void revokeClientGrant(Long realmId, Long userId, Long clientId) {
        if (realmId == null || userId == null || clientId == null) {
            return;
        }
        try {
            IamUserPrincipal principal = revokeAuthorizations(userId, clientId);
            broadcast(realmId, userId, clientId, principal);
            clientSessionStore.closeByUserAndClient(realmId, userId, clientId);
        } catch (RuntimeException e) {
            log.warn("撤回同意后收回访问失败: realmId={}, userId={}, clientId={}",
                    realmId, userId, clientId, e);
        }
    }

    /**
     * 收回单个用户对单个应用的访问。
     * <p>每步失败只留日志,不向上抛出:授权变更已经提交,收回是补偿动作,
     * 失败不应反过来影响管理端操作结果。</p>
     *
     * @param realmId      身份域主键
     * @param appServiceId 应用服务主键
     * @param userId       用户主键
     */
    private void revoke(Long realmId, Long appServiceId, Long userId) {
        try {
            ApplicationService app = appServiceManager.getById(appServiceId);
            if (app == null || app.getSsoClientPk() == null) {
                // 未绑定客户端(CAS/SAML 等)没有协议授权记录,刷新闸门是唯一出口
                return;
            }
            String visibility = app.getVisibility();
            if (VISIBILITY_PUBLIC.equals(visibility) || VISIBILITY_PERMISSION.equals(visibility)) {
                // 这两类可见性不依赖授权记录,移除 USER 授权不改变访问结果
                return;
            }
            // 用户仍可通过角色、组或其它直授进入时不能收回
            if (visibilityService.canAccessByUserId(realmId, userId, app)) {
                return;
            }
            Long clientId = app.getSsoClientPk();
            IamUserPrincipal principal = revokeAuthorizations(userId, clientId);
            broadcast(realmId, userId, clientId, principal);
            int closed = clientSessionStore.closeByUserAndClient(realmId, userId, clientId);
            log.info("收回应用访问: realmId={}, appCode={}, userId={}, clientId={}, 关闭会话={}",
                    realmId, app.getAppCode(), userId, clientId, closed);
        } catch (RuntimeException e) {
            log.warn("收回应用访问失败: realmId={}, appServiceId={}, userId={}",
                    realmId, appServiceId, userId, e);
        }
    }

    /**
     * 吊销用户在该客户端上的全部授权记录。
     * <p>访问令牌进黑名单,刷新令牌因授权记录删除而无法续期;返回最后一个可读的
     * 用户主体,供登出令牌解析 sub。</p>
     *
     * @param userId   用户主键
     * @param clientId 客户端主键
     * @return 授权记录中的用户主体;没有可读授权或记录不含主体时返回 null
     */
    private IamUserPrincipal revokeAuthorizations(Long userId, Long clientId) {
        String targetClientId = String.valueOf(clientId);
        IamUserPrincipal principal = null;
        for (OAuth2Authorization authorization : authorizationService.listByUser(userId)) {
            if (!targetClientId.equals(authorization.getRegisteredClientId())) {
                continue;
            }
            IamUserPrincipal candidate = principalOf(authorization);
            if (candidate != null) {
                principal = candidate;
            }
            authorizationService.remove(authorization);
        }
        return principal;
    }

    /**
     * 只向被收回的应用广播登出,每个会话一次。
     *
     * @param realmId   身份域主键
     * @param userId    用户主键
     * @param clientId  客户端主键
     * @param principal 用户主体;为空时登出令牌只带 sid
     */
    private void broadcast(Long realmId, Long userId, Long clientId, IamUserPrincipal principal) {
        List<ClientSessionModel> sessions = clientSessionStore.listByUserAndClient(realmId, userId, clientId);
        if (sessions.isEmpty()) {
            return;
        }
        String realmKey = realmProvider.findById(realmId).map(RealmModel::realmKey)
                .orElse(principal == null ? null : principal.getRealmKey());
        if (realmKey == null) {
            log.warn("无法确定域标识,跳过定向登出: realmId={}, userId={}, clientId={}",
                    realmId, userId, clientId);
            return;
        }
        for (ClientSessionModel session : sessions) {
            try {
                broadcastService.broadcastToClient(realmId, realmKey, session.sid(), principal, clientId);
            } catch (RuntimeException e) {
                // 应用未收到通知时,令牌已吊销,应用侧下一次请求会因验签或刷新失败下线
                log.warn("定向登出失败: realmId={}, sid={}, clientId={}",
                        realmId, session.sid(), clientId, e);
            }
        }
    }

    /**
     * 从授权属性中取登录主体快照。
     *
     * @param authorization 授权记录
     * @return 登录主体;属性中不含本项目主体时返回 null
     */
    private static IamUserPrincipal principalOf(OAuth2Authorization authorization) {
        Object attribute = authorization.getAttribute(Principal.class.getName());
        if (attribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof IamUserPrincipal user) {
            return user;
        }
        return null;
    }
}
