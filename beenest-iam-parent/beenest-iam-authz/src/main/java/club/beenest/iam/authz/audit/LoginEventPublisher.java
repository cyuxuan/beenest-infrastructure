package club.beenest.iam.authz.audit;

import club.beenest.iam.common.event.LoginEventEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 登录事件发布器。
 * <p>认证链路各失败点与登录成功点调用本组件发布事件,由监听器旁路落库。
 * 发布失败(如无监听器)不得影响认证主流程,故发布动作本身包在 try 中。</p>
 */
@Component
public class LoginEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public LoginEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布登录事件。
     *
     * @param realmKey   域标识
     * @param userId     用户主键(账号不存在时传 null)
     * @param username   登录名
     * @param eventType  事件类型(见 {@link LoginEventEvent} 常量)
     * @param loginType  登录方式
     * @param ip         来源 IP
     * @param userAgent  浏览器标识
     * @param failReason 失败原因(成功事件传 null)
     */
    public void publish(String realmKey, Long userId, String username, String eventType,
                        String loginType, String ip, String userAgent, String failReason) {
        try {
            eventPublisher.publishEvent(new LoginEventEvent(
                    realmKey, userId, username, eventType, loginType,
                    ip, userAgent, failReason, LocalDateTime.now()));
        } catch (Exception e) {
            // 事件是旁路证据,不能因为它让登录本身失败
            org.slf4j.LoggerFactory.getLogger(LoginEventPublisher.class)
                    .warn("登录事件发布失败: username={}, type={}", username, eventType, e);
        }
    }
}
