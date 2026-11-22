package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.LoginPolicyResponse;
import club.beenest.iam.admin.dto.LoginPolicyUpdateRequest;
import club.beenest.iam.admin.dto.PasswordPolicyResponse;
import club.beenest.iam.admin.dto.PasswordPolicyUpdateRequest;
import club.beenest.iam.admin.service.SecurityPolicyService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全策略管理接口(口令策略 + 登录策略)。
 * <p>每域一条策略行,查询与更新在策略缺失时按缺省值创建。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/security-policies")
public class SecurityPolicyController {

    private final SecurityPolicyService securityPolicyService;

    public SecurityPolicyController(SecurityPolicyService securityPolicyService) {
        this.securityPolicyService = securityPolicyService;
    }

    /**
     * 查询当前域口令策略。
     *
     * @return 口令策略,不存在时按缺省值创建后返回
     */
    @GetMapping("/password")
    @PreAuthorize("hasAuthority('security-policy:view')")
    public Result<PasswordPolicyResponse> getPasswordPolicy() {
        return Result.success(securityPolicyService.getPasswordPolicy());
    }

    /**
     * 更新当前域口令策略。
     *
     * @param request 口令策略更新请求
     * @return 更新后的口令策略
     * @throws club.beenest.iam.common.exception.BusinessException 最小长度大于最大长度
     */
    @PutMapping("/password")
    @PreAuthorize("hasAuthority('security-policy:update')")
    @LogAudit(action = "PASSWORD_POLICY_UPDATE", description = "更新口令策略")
    public Result<PasswordPolicyResponse> updatePasswordPolicy(
            @Valid @RequestBody PasswordPolicyUpdateRequest request) {
        return Result.success(securityPolicyService.updatePasswordPolicy(request));
    }

    /**
     * 查询当前域登录策略。
     *
     * @return 登录策略,不存在时按缺省值创建后返回
     */
    @GetMapping("/login")
    @PreAuthorize("hasAuthority('security-policy:view')")
    public Result<LoginPolicyResponse> getLoginPolicy() {
        return Result.success(securityPolicyService.getLoginPolicy());
    }

    /**
     * 更新当前域登录策略。
     * <p>登录策略带 Redis 缓存,更新后同步失效缓存。</p>
     *
     * @param request 登录策略更新请求
     * @return 更新后的登录策略
     */
    @PutMapping("/login")
    @PreAuthorize("hasAuthority('security-policy:update')")
    @LogAudit(action = "LOGIN_POLICY_UPDATE", description = "更新登录策略")
    public Result<LoginPolicyResponse> updateLoginPolicy(
            @Valid @RequestBody LoginPolicyUpdateRequest request) {
        return Result.success(securityPolicyService.updateLoginPolicy(request));
    }
}
