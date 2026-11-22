package club.beenest.iam.web.controller;

import club.beenest.iam.web.config.IamThemeSelector;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * MFA 二步验证页控制器。
 * <p>口令认证通过后,表单登录由失败处理器重定向到本页,
 * REST 登录由前端凭票据跳转;页面通过 fetch 提交动态口令到
 * {@code /api/v1/auth/login-mfa},成功后按回跳地址跳转。</p>
 */
@Controller
public class MfaPageController {

    private final IamThemeSelector themeSelector;

    public MfaPageController(IamThemeSelector themeSelector) {
        this.themeSelector = themeSelector;
    }

    /**
     * 渲染 MFA 验证页。
     *
     * @param realm   身份域 key(缺省 default)
     * @param mfaToken 口令认证通过后签发的一次性票据
     * @param enroll  是否要求先完成动态口令绑定(域策略强制 MFA)
     * @param model   视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化 MFA 验证页视图名
     */
    @GetMapping("/login-mfa")
    public String mfaPage(@RequestParam(value = "realm", defaultValue = "default") String realm,
                          @RequestParam(value = "mfaToken", required = false) String mfaToken,
                          @RequestParam(value = "enroll", required = false) String enroll,
                          Model model,
                          CsrfToken csrfToken) {
        model.addAttribute("realmKey", realm);
        model.addAttribute("mfaToken", mfaToken == null ? "" : mfaToken);
        model.addAttribute("enrollMode", "1".equals(enroll));
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("login/mfa");
    }
}
