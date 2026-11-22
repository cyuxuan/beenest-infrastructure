package club.beenest.iam.console.controller;

import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.console.dto.MfaEnrollRequest;
import club.beenest.iam.console.dto.MfaDisableRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户自助 MFA 管理接口。
 * <p>动态口令绑定分两步:先取密钥与 provisioning URI,用户添加后提交一个动态口令完成启用;
 * 关闭时需通过一次动态口令或恢复码校验。</p>
 */
@RestController
@RequestMapping("/api/v1/console/mfa")
public class MfaController {

    private final MfaService mfaService;
    private final RequiredActionService requiredActionService;

    public MfaController(MfaService mfaService, RequiredActionService requiredActionService) {
        this.mfaService = mfaService;
        this.requiredActionService = requiredActionService;
    }

    /**
     * 查询本人 MFA 状态。
     *
     * @return MFA 启用状态与剩余恢复码数量
     */
    @GetMapping("/status")
    public Result<MfaService.MfaStatus> status() {
        return Result.success(mfaService.status(CurrentUserHolder.require().getUserId()));
    }

    /**
     * 开始绑定动态口令:返回密钥与 provisioning URI。
     * <p>密钥仅此一次明文返回,需提示用户立即在 authenticator 中完成添加。</p>
     *
     * @return 密钥与 provisioning URI
     */
    @PostMapping("/enroll")
    public Result<MfaService.TotpEnrollment> enroll() {
        var principal = CurrentUserHolder.require();
        return Result.success(mfaService.beginEnrollment(
                principal.getRealmId(), principal.getUserId(), principal.getUsername(), principal.getRealmKey()));
    }

    /**
     * 确认绑定:校验 authenticator 生成的动态口令,启用并发放恢复码。
     *
     * @param request 确认请求,携带当前 6 位动态口令
     * @return 恢复码明文列表(仅此一次返回)
     */
    @PostMapping("/confirm")
    public Result<List<String>> confirm(@Valid @RequestBody MfaEnrollRequest request) {
        Long userId = CurrentUserHolder.require().getUserId();
        List<String> recoveryCodes = mfaService.confirmEnrollment(userId, request.code());
        // 绑定完成即摘除 CONFIGURE_TOTP 待办
        requiredActionService.complete(null, userId, RequiredAction.CONFIGURE_TOTP);
        return Result.success(recoveryCodes);
    }

    /**
     * 重新发放恢复码(旧的立即作废)。
     *
     * @return 新的恢复码明文列表
     */
    @PostMapping("/recovery-codes")
    public Result<List<String>> regenerateRecoveryCodes() {
        Long userId = CurrentUserHolder.require().getUserId();
        return Result.success(mfaService.regenerateRecoveryCodes(userId));
    }

    /**
     * 关闭动态口令:需通过一次动态口令或恢复码校验。
     *
     * @param request 关闭请求,携带动态口令或恢复码
     * @return 无业务数据的成功响应
     */
    @PostMapping("/disable")
    public Result<Void> disable(@Valid @RequestBody MfaDisableRequest request) {
        Long userId = CurrentUserHolder.require().getUserId();
        mfaService.disable(userId, request.code());
        return Result.success();
    }
}
