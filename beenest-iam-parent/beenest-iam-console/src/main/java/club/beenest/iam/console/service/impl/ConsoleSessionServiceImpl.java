package club.beenest.iam.console.service.impl;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.console.dto.ConsoleSessionResponse;
import club.beenest.iam.console.service.ConsoleSessionService;
import club.beenest.iam.core.model.UserSessionModel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话自助服务实现。
 */
@Service
public class ConsoleSessionServiceImpl implements ConsoleSessionService {

    private final IamUserSessionService userSessionService;
    private final IamLogoutService logoutService;

    public ConsoleSessionServiceImpl(IamUserSessionService userSessionService, IamLogoutService logoutService) {
        this.userSessionService = userSessionService;
        this.logoutService = logoutService;
    }

    @Override
    public List<ConsoleSessionResponse> list(String currentSid) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 按建立时间倒序展示最近登录的设备
        return userSessionService.listSessions(principal.getRealmId(), principal.getUserId()).stream()
                .map(session -> toResponse(session, currentSid))
                .toList();
    }

    @Override
    public void revoke(String sid, String currentSid) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 1. 目标会话必须存在
        UserSessionModel session = userSessionService.findBySid(sid);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sid);
        }
        // 2. 只能注销自己的会话
        if (!principal.getUserId().equals(session.userId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sid);
        }
        // 3. 当前会话请走注销接口,避免注销成功后当前请求处于半失效状态
        if (sid.equals(currentSid)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "当前会话请使用注销接口");
        }
        // 走统一登出入口:吊销该会话签出的令牌,并通知应用下线
        logoutService.logout(sid);
    }

    /**
     * 会话模型转响应。
     * <p>在线与否以 Redis 快照为准:数据库行的活跃状态要等巡检才订正,
     * 直接照搬会把已经下线的会话显示成在线设备。</p>
     *
     * @param session    会话记录
     * @param currentSid 当前请求所用会话标识
     * @return 会话响应
     */
    private ConsoleSessionResponse toResponse(UserSessionModel session, String currentSid) {
        String state = session.state();
        if (UserSessionModel.STATE_ACTIVE.equals(state) && !userSessionService.expireIfStale(session.sid())) {
            state = UserSessionModel.STATE_EXPIRED;
        }
        return new ConsoleSessionResponse(
                session.sid(),
                session.loginType(),
                session.ip(),
                session.userAgent(),
                state,
                session.createdTime(),
                session.sid().equals(currentSid));
    }
}
