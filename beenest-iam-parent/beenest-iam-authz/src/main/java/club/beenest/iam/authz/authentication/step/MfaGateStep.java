package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.authentication.MfaRequiredException;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.persistence.entity.LoginPolicy;
import org.springframework.security.core.AuthenticationException;

/**
 * 第二因素门禁步骤。
 * <p>已绑定 TOTP 必须二步验证;域策略强制 MFA 而账号未绑定时,要求先完成绑定。
 * 口令已通过,此处抛出的是"挑战"而非"失败":登录事件由二步验证完成后记录,
 * 避免"口令正确但未完成登录"被记成失败、污染风控统计。</p>
 * <p>本步骤只判定"是否需要第二因素",具体的验证方式(动态口令 / 恢复码 / Passkey)
 * 由第二步的验证服务承担,因此新增第二因素类型不需要改动本步骤。</p>
 */
public class MfaGateStep implements AuthenticationStep {

    private final MfaService mfaService;

    public MfaGateStep(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @Override
    public Stage stage() {
        return Stage.SECOND_FACTOR;
    }

    @Override
    public String name() {
        return "mfa-gate";
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 判定是否需要进入二步验证。
     *
     * @param context 认证上下文,需已填充用户与登录方式
     * @throws AuthenticationException 需要二步验证时抛出 {@link MfaRequiredException}
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        UserModel user = context.getUser();
        boolean enrolled = mfaService.isEnrolled(user.id());
        LoginPolicy loginPolicy = context.getLoginPolicy();
        boolean forceMfa = loginPolicy != null && Boolean.TRUE.equals(loginPolicy.getForceMfa());
        if (enrolled || forceMfa) {
            throw new MfaRequiredException(user.id(), user.username(), forceMfa && !enrolled,
                    context.getLoginType());
        }
    }
}
