package club.beenest.iam.authz.audit;

import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.LoginEvent;
import club.beenest.iam.persistence.manager.IamLoginEventManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 登录事件落库监听器。
 * <p>与 {@link AuditLogListener} 同一旁路哲学:事件写入失败只告警不回滚,
 * 认证主流程不因证据链故障而中断。</p>
 */
@Component
public class LoginEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoginEventListener.class);

    private final IamLoginEventManager loginEventManager;
    private final RealmProvider realmProvider;

    public LoginEventListener(IamLoginEventManager loginEventManager, RealmProvider realmProvider) {
        this.loginEventManager = loginEventManager;
        this.realmProvider = realmProvider;
    }

    /**
     * 消费登录事件并写入 {@code iam_login_event}。
     * <p>写入失败只记日志,不影响登录流程。</p>
     *
     * @param event 登录事件,携带域标识、账号、登录方式与结果
     */
    @EventListener
    public void onLoginEvent(LoginEventEvent event) {
        try {
            LoginEvent record = new LoginEvent();
            record.setRealmId(resolveRealmId(event.realmKey()));
            record.setUserId(event.userId());
            record.setUsername(event.username());
            record.setEventType(event.eventType());
            record.setLoginType(event.loginType());
            record.setIp(event.ip());
            record.setUserAgent(event.userAgent());
            record.setFailReason(event.failReason());
            record.setOccurredTime(event.occurredAt());
            loginEventManager.record(record);
        } catch (Exception e) {
            log.error("登录事件写入失败: username={}, type={}",
                    event.username(), event.eventType(), e);
        }
    }

    /**
     * 按域标识回查域主键。
     *
     * @param realmKey 域标识,可为空
     * @return 域主键;标识为空或域不存在时返回 null,事件仍留痕且 realm_id 为空表示系统级
     */
    private Long resolveRealmId(String realmKey) {
        if (realmKey == null || realmKey.isBlank()) {
            return null;
        }
        return realmProvider.findByRealmKey(realmKey).map(RealmModel::id).orElse(null);
    }
}
