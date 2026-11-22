package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.core.model.RealmModel;

/**
 * 失败计数清理步骤。
 * <p>凭据与账号状态都已通过后清除该账号的失败计数。位置放在状态校验之后而不是凭据校验之后:
 * 账号被禁用时不应清除计数,否则"禁用账号被反复尝试"这一信号会被抹掉。</p>
 */
public class FailureResetStep implements AuthenticationStep {

    private final LoginFailureCounter loginFailureCounter;

    public FailureResetStep(LoginFailureCounter loginFailureCounter) {
        this.loginFailureCounter = loginFailureCounter;
    }

    @Override
    public Stage stage() {
        return Stage.POST_CHECK;
    }

    @Override
    public String name() {
        return "failure-reset";
    }

    @Override
    public int order() {
        return 20;
    }

    /**
     * 清除账号维度的失败计数。
     * <p>只清账号维度,不动来源 IP 计数:同一来源可能同时在试多个账号。</p>
     *
     * @param context 认证上下文,需已填充身份域与登录名
     */
    @Override
    public void execute(AuthenticationContext context) {
        RealmModel realm = context.getRealm();
        loginFailureCounter.onSuccess(realm.realmKey(), context.getUsername());
    }
}
