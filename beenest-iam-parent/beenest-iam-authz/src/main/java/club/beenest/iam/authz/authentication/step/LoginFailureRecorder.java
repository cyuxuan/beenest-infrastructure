package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 登录失败审计记录器。
 * <p>把"记录一次失败"从各步骤中收敛到一处,保证失败原因、来源地址与 User-Agent 的取法一致。
 * 失败原因只进审计,不对外返回。</p>
 */
public class LoginFailureRecorder {

    private final LoginEventPublisher loginEventPublisher;

    public LoginFailureRecorder(LoginEventPublisher loginEventPublisher) {
        this.loginEventPublisher = loginEventPublisher;
    }

    /**
     * 记录一次登录失败。
     * <p>账号不存在时 userId 传 null,以登录名留痕:不存在的账号被反复尝试本身就是撞库信号,
     * 需在审计中保留。身份域不可用时 realmKey 记为空。</p>
     *
     * @param context 当前认证上下文,提供登录名、身份域与请求
     * @param userId  已解析出的账号主键;账号不存在时为 null
     * @param reason  写入审计的真实失败原因,仅内部可见
     */
    public void record(AuthenticationContext context, Long userId, String reason) {
        HttpServletRequest request = context.getRequest();
        RealmModel realm = context.getRealm();
        loginEventPublisher.publish(
                realm == null ? null : realm.realmKey(),
                userId,
                context.getUsername(),
                LoginEventEvent.LOGIN_FAILURE,
                "PASSWORD",
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : request.getHeader("User-Agent"),
                reason);
    }
}
