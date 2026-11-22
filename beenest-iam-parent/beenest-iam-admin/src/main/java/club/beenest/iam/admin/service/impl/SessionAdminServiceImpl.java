package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AdminSessionResponse;
import club.beenest.iam.admin.dto.LogoutBroadcastResponse;
import club.beenest.iam.admin.service.SessionAdminService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.LogoutBroadcastModel;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.LogoutBroadcastStore;
import club.beenest.iam.persistence.entity.UserSession;
import club.beenest.iam.persistence.manager.IamUserSessionManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端会话管理服务实现。
 * <p>会话与广播记录按当前身份域隔离;强制注销复用协议侧登出编排,
 * 与用户主动退出走同一条路径,保证业务系统同步失效。</p>
 */
@Service
public class SessionAdminServiceImpl implements SessionAdminService {

    private final IamUserSessionManager sessionManager;
    private final IamUserSessionService userSessionService;
    private final IamLogoutService logoutService;
    private final LogoutBroadcastStore broadcastStore;

    public SessionAdminServiceImpl(IamUserSessionManager sessionManager,
                                   IamUserSessionService userSessionService,
                                   IamLogoutService logoutService,
                                   LogoutBroadcastStore broadcastStore) {
        this.sessionManager = sessionManager;
        this.userSessionService = userSessionService;
        this.logoutService = logoutService;
        this.broadcastStore = broadcastStore;
    }

    @Override
    public PageResult<AdminSessionResponse> page(String username, String state, int pageNum, int pageSize) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        PageResult<UserSession> page = sessionManager.page(realmId, username, state, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    @Override
    public void revoke(String sid) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 1. 会话必须属于当前域,防止跨域踢会话
        UserSessionModel session = userSessionService.findBySid(sid);
        if (session == null || !realmId.equals(session.realmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sid);
        }
        // 2. 结束会话:广播单点登出 + 删除 Redis 快照 + 记录置为已注销,
        //    与用户主动退出同一条路径,业务系统同步失效
        logoutService.logout(sid);
    }

    @Override
    public List<LogoutBroadcastResponse> broadcasts(String sid) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return broadcastStore.listBySid(realmId, sid).stream().map(this::toResponse).toList();
    }

    @Override
    public List<LogoutBroadcastResponse> recentBroadcasts(int limit) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return broadcastStore.listRecent(realmId, limit).stream().map(this::toResponse).toList();
    }

    /**
     * 登出广播记录转响应。
     *
     * @param model 广播记录
     * @return 广播响应
     */
    private LogoutBroadcastResponse toResponse(LogoutBroadcastModel model) {
        return new LogoutBroadcastResponse(model.id(), model.sid(), model.clientKey(), model.targetUri(),
                model.state(), model.httpStatus(), model.errorMessage(), model.createdTime());
    }

    /**
     * 会话实体转响应。
     * <p>在线与否以 Redis 快照为准:数据库行的活跃状态要等巡检才订正,
     * 直接照搬会把已经下线的会话留在在线列表里。</p>
     *
     * @param session 会话记录
     * @return 会话响应
     */
    private AdminSessionResponse toResponse(UserSession session) {
        String state = session.getState();
        if (UserSessionModel.STATE_ACTIVE.equals(state) && !userSessionService.expireIfStale(session.getSid())) {
            state = UserSessionModel.STATE_EXPIRED;
        }
        return new AdminSessionResponse(session.getId(), session.getSid(), session.getUserId(),
                session.getLoginType(), session.getIp(), session.getUserAgent(), state,
                session.getCreatedTime(), session.getLastActiveTime());
    }
}
