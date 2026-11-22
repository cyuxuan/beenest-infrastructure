package club.beenest.iam.web.controller;

import club.beenest.iam.authz.mail.MailChannelRouter;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.security.EmailVerificationService;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.web.config.IamThemeSelector;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 账户自助页面控制器。
 * <p>四个页面:找回口令、重置口令、邮箱验证结果、账户待办。
 * 前三个在未登录状态下可达,待办页要求已登录。</p>
 */
@Controller
public class AccountPageController {

    private final IamThemeSelector themeSelector;
    private final MailChannelRouter mailChannelRouter;
    private final RequiredActionService requiredActionService;
    private final EmailVerificationService emailVerificationService;
    private final WebAuthnProperties webAuthnProperties;
    private final MessageSource messageSource;

    public AccountPageController(IamThemeSelector themeSelector, MailChannelRouter mailChannelRouter,
                                 RequiredActionService requiredActionService,
                                 EmailVerificationService emailVerificationService,
                                 WebAuthnProperties webAuthnProperties, MessageSource messageSource) {
        this.themeSelector = themeSelector;
        this.mailChannelRouter = mailChannelRouter;
        this.requiredActionService = requiredActionService;
        this.emailVerificationService = emailVerificationService;
        this.webAuthnProperties = webAuthnProperties;
        this.messageSource = messageSource;
    }

    /**
     * 渲染找回口令页。
     *
     * @param realm     身份域标识
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化找回口令页视图名
     */
    @GetMapping("/forgot-password")
    public String forgotPassword(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                 Model model, CsrfToken csrfToken) {
        model.addAttribute("realmKey", realm);
        // 邮件通道不可用时页面只保留短信找回入口
        model.addAttribute("mailAvailable", mailChannelRouter.available());
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("account/forgot-password");
    }

    /**
     * 渲染重置口令页。
     *
     * @param realm     身份域标识
     * @param ticket    邮件链接携带的重置票据
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化重置口令页视图名
     */
    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                @RequestParam(value = "ticket", required = false) String ticket,
                                Model model, CsrfToken csrfToken) {
        model.addAttribute("realmKey", realm);
        model.addAttribute("ticket", ticket == null ? "" : ticket);
        model.addAttribute("ticketMissing", ticket == null || ticket.isBlank());
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("account/reset-password");
    }

    /**
     * 邮箱验证落地页:消费票据并展示结果。
     * <p>票据无效时渲染同一个页面、只换成失败文案,不区分"票据已用过"
     * 与"票据不存在"。</p>
     *
     * @param realm  身份域标识
     * @param ticket 验证邮件携带的票据,可为空
     * @param model  视图模型
     * @return 主题化验证结果页视图名
     */
    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(value = "realm", defaultValue = "default") String realm,
                              @RequestParam(value = "ticket", required = false) String ticket,
                              Model model) {
        model.addAttribute("realmKey", realm);
        boolean success = false;
        String message = text("page.verifyEmail.failMessage");
        if (ticket != null && !ticket.isBlank()) {
            try {
                emailVerificationService.confirm(realm, ticket);
                success = true;
                message = text("page.verifyEmail.successMessage");
            } catch (BusinessException e) {
                message = e.getMessage();
            }
        }
        model.addAttribute("success", success);
        model.addAttribute("message", message);
        return themeSelector.view("account/verify-email");
    }

    /**
     * 账户待办页:列出未完成事项并给出办理入口。
     * <p>没有待办时直接回门户:完成最后一项后页面会刷新,登录回跳也可能落在这里;
     * 空页面没有可点的出口,用户只能自己再登出登入一次。</p>
     *
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化待办页视图名;无待办时为门户的重定向地址
     */
    @GetMapping("/required-action")
    public String requiredAction(Model model, CsrfToken csrfToken) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        Set<RequiredAction> pending = requiredActionService.pending(principal.getUserId());
        if (pending.isEmpty()) {
            return "redirect:" + PortalPageController.PORTAL_PATH;
        }
        List<ActionItem> items = new ArrayList<>();
        for (RequiredAction action : pending) {
            items.add(new ActionItem(action.name(),
                    text("page.action.name." + action.name()),
                    text("page.action.desc." + action.name())));
        }
        model.addAttribute("realmKey", principal.getRealmKey());
        model.addAttribute("actions", items);
        model.addAttribute("mailAvailable", mailChannelRouter.available());
        model.addAttribute("passkeyAvailable", webAuthnProperties.isAvailable());
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("account/required-action");
    }

    /**
     * 按当前请求语言取文案。
     *
     * @param key 资源键
     * @return 文案;键缺失时返回键名本身,便于在页面上直接看出缺哪条
     */
    private String text(String key) {
        return messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }

    /**
     * 待办事项的展示条目。
     *
     * @param code        动作代码
     * @param name        展示名
     * @param description 说明文案
     */
    public record ActionItem(String code, String name, String description) {
    }
}
