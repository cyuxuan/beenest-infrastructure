package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

/**
 * 失败锁定检查步骤。
 * <p>先查账号维度、再查来源 IP 维度。两个维度缺一不可:只有账号维度时,
 * 攻击者用同一 IP 轮换账号做密码喷洒,每个账号只失败一次,账号永远不会被锁。</p>
 * <p>对外统一为"账号或密码错误":单独暴露锁定状态会把账号是否存在、
 * 是否正在被撞库泄露给攻击者,真实原因写入审计。</p>
 */
public class AccountLockStep implements AuthenticationStep {

    private final LoginFailureCounter loginFailureCounter;
    private final LoginFailureRecorder failureRecorder;

    public AccountLockStep(LoginFailureCounter loginFailureCounter, LoginFailureRecorder failureRecorder) {
        this.loginFailureCounter = loginFailureCounter;
        this.failureRecorder = failureRecorder;
    }

    @Override
    public Stage stage() {
        return Stage.PRE_CHECK;
    }

    @Override
    public String name() {
        return "account-lock";
    }

    @Override
    public int order() {
        return 20;
    }

    /**
     * 检查账号与来源 IP 是否处于锁定窗口。
     *
     * @param context 认证上下文,需已填充身份域与登录策略
     * @throws AuthenticationException 账号或来源 IP 处于锁定期时抛出
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        RealmModel realm = context.getRealm();
        String username = context.getUsername();
        if (loginFailureCounter.isLocked(realm.realmKey(), username, context.getLoginPolicy())) {
            failureRecorder.record(context, null, "失败次数过多,账号已临时锁定");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        String clientIp = context.getClientIp();
        if (loginFailureCounter.isIpLocked(realm.realmKey(), clientIp, context.getLoginPolicy())) {
            failureRecorder.record(context, null, "来源 IP 失败次数过多,已临时锁定");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
    }
}
