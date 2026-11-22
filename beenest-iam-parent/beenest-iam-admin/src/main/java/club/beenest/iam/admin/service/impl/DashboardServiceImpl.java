package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.DashboardStatsResponse;
import club.beenest.iam.admin.service.DashboardService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.persistence.manager.IamLoginEventManager;
import club.beenest.iam.persistence.mapper.ClientMapper;
import club.beenest.iam.persistence.mapper.GroupMapper;
import club.beenest.iam.persistence.mapper.UserCredentialMapper;
import club.beenest.iam.persistence.mapper.UserMapper;
import club.beenest.iam.persistence.mapper.UserSessionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 运营仪表盘统计实现。
 * <p>统计范围限定在当前身份域;用户状态分布一次分组查询得出,登录事件按今日零点起汇总,
 * MFA 绑定数按启用态 TOTP 凭证去重用户统计。</p>
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    /** 账号状态:正常 */
    private static final int STATUS_NORMAL = 1;
    /** 账号状态:禁用 */
    private static final int STATUS_DISABLED = 0;
    /** 账号状态:锁定 */
    private static final int STATUS_LOCKED = 2;

    private final UserMapper userMapper;
    private final ClientMapper clientMapper;
    private final GroupMapper groupMapper;
    private final UserSessionMapper userSessionMapper;
    private final UserCredentialMapper userCredentialMapper;
    private final IamLoginEventManager loginEventManager;

    public DashboardServiceImpl(UserMapper userMapper, ClientMapper clientMapper,
                                GroupMapper groupMapper, UserSessionMapper userSessionMapper,
                                UserCredentialMapper userCredentialMapper,
                                IamLoginEventManager loginEventManager) {
        this.userMapper = userMapper;
        this.clientMapper = clientMapper;
        this.groupMapper = groupMapper;
        this.userSessionMapper = userSessionMapper;
        this.userCredentialMapper = userCredentialMapper;
        this.loginEventManager = loginEventManager;
    }

    @Override
    public DashboardStatsResponse stats() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 1. 用户状态分布(一次分组查询)
        Map<String, Long> byStatus = countUsersByStatus(realmId);
        long userTotal = byStatus.values().stream().mapToLong(Long::longValue).sum();
        // 2. 当日登录事件汇总
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Long> byEventType = loginEventManager.countByTypeSince(realmId, todayStart);
        // 3. 组装响应
        return new DashboardStatsResponse(
                userTotal,
                byStatus.getOrDefault(String.valueOf(STATUS_NORMAL), 0L),
                byStatus.getOrDefault(String.valueOf(STATUS_DISABLED), 0L),
                byStatus.getOrDefault(String.valueOf(STATUS_LOCKED), 0L),
                clientMapper.countByRealm(realmId),
                groupMapper.countByRealm(realmId),
                userSessionMapper.countActiveByRealm(realmId),
                userCredentialMapper.countEnabledUsersByType(realmId, MfaService.CREDENTIAL_TOTP),
                byEventType.getOrDefault(LoginEventEvent.LOGIN_SUCCESS, 0L),
                byEventType.getOrDefault(LoginEventEvent.LOGIN_FAILURE, 0L),
                byEventType.getOrDefault(LoginEventEvent.MFA_FAILURE, 0L),
                byEventType.getOrDefault(LoginEventEvent.LOGOUT, 0L));
    }

    /**
     * 按状态统计用户数量。
     *
     * @param realmId 身份域主键
     * @return 状态值与数量的映射,键为状态值的字符串形式
     */
    private Map<String, Long> countUsersByStatus(Long realmId) {
        Map<String, Long> result = new java.util.HashMap<>();
        for (Map<String, Object> row : userMapper.countByStatusGroup(realmId)) {
            Object status = row.get("status");
            Object total = row.get("total");
            if (status != null && total instanceof Number number) {
                result.put(String.valueOf(status), number.longValue());
            }
        }
        return result;
    }
}
