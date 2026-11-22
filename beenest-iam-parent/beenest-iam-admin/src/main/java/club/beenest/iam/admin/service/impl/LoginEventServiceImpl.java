package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.LoginEventResponse;
import club.beenest.iam.admin.service.LoginEventService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.LoginEvent;
import club.beenest.iam.persistence.manager.IamLoginEventManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 登录事件查询实现。
 * <p>事件按身份域过滤,调用方只能看到本域事件。</p>
 */
@Service
public class LoginEventServiceImpl implements LoginEventService {

    private final IamLoginEventManager loginEventManager;

    public LoginEventServiceImpl(IamLoginEventManager loginEventManager) {
        this.loginEventManager = loginEventManager;
    }

    @Override
    public PageResult<LoginEventResponse> page(String username, String eventType, String ip,
                                               LocalDateTime fromTime, LocalDateTime toTime,
                                               int pageNum, int pageSize) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        PageResult<LoginEvent> page = loginEventManager.page(
                realmId, username, eventType, ip, fromTime, toTime, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    @Override
    public PageResult<LoginEventResponse> ipHistory(String ip, int pageNum, int pageSize) {
        if (ip == null || ip.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "IP 地址不能为空");
        }
        Long realmId = CurrentUserHolder.require().getRealmId();
        PageResult<LoginEvent> page = loginEventManager.page(
                realmId, null, null, ip, null, null, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    /**
     * 登录事件转响应。
     *
     * @param event 登录事件
     * @return 登录事件响应
     */
    private LoginEventResponse toResponse(LoginEvent event) {
        return new LoginEventResponse(
                event.getId(),
                event.getUserId(),
                event.getUsername(),
                event.getEventType(),
                event.getLoginType(),
                event.getIp(),
                event.getUserAgent(),
                event.getFailReason(),
                event.getOccurredTime());
    }
}
