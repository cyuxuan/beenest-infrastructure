package club.beenest.iam.console.controller;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.rest.dto.WebAuthnCredentialResponse;
import club.beenest.iam.authz.rest.dto.WebAuthnRegisterRequest;
import club.beenest.iam.authz.webauthn.WebAuthnCredentialService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.console.dto.WebAuthnRenameRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户自助 Passkey 管理接口。
 * <p>注册分两步:先取创建选项,浏览器调用 {@code navigator.credentials.create()} 后提交结果完成绑定。
 * 第二因子由设备私钥产生,平台侧不保存共享密钥。</p>
 * <p>自助端只校验是否属于本人,不使用管理端权限码。</p>
 */
@RestController
@RequestMapping("/api/v1/console/webauthn")
public class WebAuthnController {

    private final WebAuthnCredentialService credentialService;
    private final RequiredActionService requiredActionService;

    public WebAuthnController(WebAuthnCredentialService credentialService,
                              RequiredActionService requiredActionService) {
        this.credentialService = credentialService;
        this.requiredActionService = requiredActionService;
    }

    /**
     * 查询 Passkey 能力与本人绑定数量。
     *
     * @return {@code available} 表示能力是否可用,{@code count} 表示已绑定数量
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        IamUserPrincipal principal = CurrentUserHolder.require();
        return Result.success(Map.of(
                "available", credentialService.isAvailable(),
                "count", credentialService.count(principal.getRealmId(), principal.getUserId())));
    }

    /**
     * 列出本人已绑定的凭证。
     *
     * @return 凭证列表
     */
    @GetMapping("/credentials")
    public Result<List<WebAuthnCredentialResponse>> credentials() {
        IamUserPrincipal principal = CurrentUserHolder.require();
        return Result.success(credentialService.list(principal.getRealmId(), principal.getUserId()));
    }

    /**
     * 开始绑定:返回浏览器创建选项。
     *
     * @return 创建选项(challenge / rp / user / pubKeyCredParams 等)
     */
    @PostMapping("/register/options")
    public Result<Map<String, Object>> registerOptions() {
        IamUserPrincipal principal = CurrentUserHolder.require();
        return Result.success(credentialService.beginRegistration(principal.getRealmKey(),
                principal.getRealmId(), principal.getUserId(), principal.getUsername()));
    }

    /**
     * 完成绑定。
     *
     * @param request 浏览器返回的凭证数据
     * @return 新建凭证的视图
     */
    @PostMapping("/register")
    @LogAudit(action = "CONSOLE_WEBAUTHN_REGISTER", description = "绑定 Passkey")
    public Result<WebAuthnCredentialResponse> register(@Valid @RequestBody WebAuthnRegisterRequest request) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        WebAuthnCredentialResponse response = credentialService.finishRegistration(
                principal.getRealmKey(), principal.getRealmId(), principal.getUserId(), request);
        // 绑定完成即摘除 CONFIGURE_PASSKEY 待办
        requiredActionService.complete(null, principal.getUserId(), RequiredAction.CONFIGURE_PASSKEY);
        return Result.success(response);
    }

    /**
     * 重命名凭证。
     *
     * @param id      凭证主键
     * @param request 新名称
     * @return 空响应
     */
    @PutMapping("/credentials/{id}")
    @LogAudit(action = "CONSOLE_WEBAUTHN_RENAME", description = "重命名 Passkey")
    public Result<Void> rename(@PathVariable("id") Long id,
                               @Valid @RequestBody WebAuthnRenameRequest request) {
        credentialService.rename(CurrentUserHolder.require().getUserId(), id, request.displayName());
        return Result.success();
    }

    /**
     * 删除凭证。
     *
     * @param id 凭证主键
     * @return 空响应
     */
    @DeleteMapping("/credentials/{id}")
    @LogAudit(action = "CONSOLE_WEBAUTHN_DELETE", description = "删除 Passkey")
    public Result<Void> delete(@PathVariable("id") Long id) {
        credentialService.delete(CurrentUserHolder.require().getUserId(), id);
        return Result.success();
    }
}
