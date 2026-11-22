package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.UserModel;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;

/**
 * 账号状态校验步骤。
 * <p>状态检查放在凭据校验之后:账号状态会泄露账号是否存在,
 * 先要求持有正确口令,攻击者便无法用状态差异枚举账号。</p>
 * <p>服务账号(机器身份)不得交互式登录:给它留一条口令登录通道,
 * 等于把机器身份变成可被人占用的账号。</p>
 */
public class AccountStatusStep implements AuthenticationStep {

    /** 用户类型:服务账号(机器身份,仅供凭据模式使用) */
    private static final String TYPE_SERVICE = "SERVICE";

    /** 账号可用状态码 */
    private static final int STATUS_ACTIVE = 1;

    private final LoginFailureRecorder failureRecorder;

    public AccountStatusStep(LoginFailureRecorder failureRecorder) {
        this.failureRecorder = failureRecorder;
    }

    @Override
    public Stage stage() {
        return Stage.POST_CHECK;
    }

    @Override
    public String name() {
        return "account-status";
    }

    @Override
    public int order() {
        return 10;
    }

    /**
     * 校验账号状态与账号类型。
     *
     * @param context 认证上下文,需已由凭据步骤写入用户
     * @throws AuthenticationException 账号不可用或为服务账号时抛出
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        UserModel user = context.getUser();
        if (user.status() != STATUS_ACTIVE) {
            failureRecorder.record(context, user.id(), "账号已禁用或锁定");
            throw new DisabledException(IamMessages.message(ErrorCode.ACCOUNT_LOCKED));
        }
        if (TYPE_SERVICE.equals(user.userType())) {
            failureRecorder.record(context, user.id(), "服务账号不支持交互式登录");
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
    }
}
