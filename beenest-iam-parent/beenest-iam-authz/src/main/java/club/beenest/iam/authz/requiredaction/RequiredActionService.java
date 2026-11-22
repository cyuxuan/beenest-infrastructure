package club.beenest.iam.authz.requiredaction;

import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 账户待办事项服务。
 * <p>待办事项的权威值存在 {@code iam_user.required_actions},会话里保存副本;
 * 变更需同时落库与同步当前会话,只改其一会造成两处不一致:
 * 只改会话时其它设备的登录读不到新待办,只改库时当前会话要到重新登录才生效。</p>
 */
@Service
public class RequiredActionService {

    private static final Logger log = LoggerFactory.getLogger(RequiredActionService.class);

    private final IamUserManager userManager;
    private final UserProvider userProvider;
    private final IamUserSessionService userSessionService;

    public RequiredActionService(IamUserManager userManager, UserProvider userProvider,
                                 IamUserSessionService userSessionService) {
        this.userManager = userManager;
        this.userProvider = userProvider;
        this.userSessionService = userSessionService;
    }

    /**
     * 查询用户当前未完成的待办事项。
     *
     * @param userId 用户主键
     * @return 待办集合;用户不存在或主键为空时返回空集合
     */
    public Set<RequiredAction> pending(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return userProvider.findById(userId).map(user -> {
            Set<RequiredAction> actions = user.requiredActions();
            return actions == null ? Set.<RequiredAction>of() : actions;
        }).orElse(Set.of());
    }

    /**
     * 追加待办事项,已存在的不重复添加。
     * <p>按合并语义写入:本次追加不影响此前已挂载的其它事项。</p>
     *
     * @param userId  用户主键
     * @param actions 待追加的事项,数组中的 null 元素被忽略
     * @return 追加后的完整集合
     */
    @Transactional
    public Set<RequiredAction> add(Long userId, RequiredAction... actions) {
        Set<RequiredAction> merged = new LinkedHashSet<>(pending(userId));
        if (actions != null) {
            for (RequiredAction action : actions) {
                if (action != null) {
                    merged.add(action);
                }
            }
        }
        userManager.updateRequiredActions(userId, merged);
        return merged;
    }

    /**
     * 标记某项待办已完成:从库中摘除,并同步当前会话。
     *
     * @param sid    当前会话标识;为空时从当前请求线程解析(不在请求线程内则只改库)
     * @param userId 用户主键
     * @param action 已完成的事项
     * @return 剩余待办集合
     */
    @Transactional
    public Set<RequiredAction> complete(String sid, Long userId, RequiredAction action) {
        Set<RequiredAction> remaining = new LinkedHashSet<>(pending(userId));
        remaining.remove(action);
        userManager.updateRequiredActions(userId, remaining);
        String sessionId = sid != null && !sid.isBlank() ? sid : currentSid();
        if (sessionId != null) {
            userSessionService.updateRequiredActions(sessionId, remaining);
        }
        log.info("账户待办已完成: userId={}, action={}, remaining={}", userId, action, remaining.size());
        return remaining;
    }

    /**
     * 取当前请求线程上的会话标识。
     *
     * @return 会话标识;不在请求线程内(如定时任务)返回 null
     */
    private static String currentSid() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return IamRedisSecurityContextRepository.currentSessionId(servletAttributes.getRequest());
        }
        return null;
    }

    /**
     * 给账号挂上"更新口令"待办,用于管理员重置口令后要求下次登录改密。
     *
     * @param userId 用户主键
     */
    @Transactional
    public void requirePasswordChange(Long userId) {
        add(userId, RequiredAction.UPDATE_PASSWORD);
    }
}
