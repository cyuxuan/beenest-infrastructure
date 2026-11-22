package club.beenest.iam.web.controller;

import club.beenest.iam.authz.account.AccountOverview;
import club.beenest.iam.authz.account.AccountOverviewService;
import club.beenest.iam.authz.account.PersonalDataExportService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.web.config.IamThemeSelector;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * 用户中心页面控制器。
 * <p>一个页面承载四类自助能力:凭证概况、已授权应用、登录设备、个人数据导出。
 * 写操作走表单 POST 后重定向(PRG),避免刷新重复提交;结果用查询参数回传,
 * 不依赖会话闪存。</p>
 */
@Controller
public class AccountCenterController {

    /** 成功提示的查询参数取值 */
    private static final String DONE_CONSENT_REVOKED = "consentRevoked";
    private static final String DONE_SESSION_REVOKED = "sessionRevoked";

    private static final Logger log = LoggerFactory.getLogger(AccountCenterController.class);

    private final IamThemeSelector themeSelector;
    private final AccountOverviewService overviewService;
    private final PersonalDataExportService exportService;
    private final MessageSource messageSource;

    public AccountCenterController(IamThemeSelector themeSelector, AccountOverviewService overviewService,
                                   PersonalDataExportService exportService, MessageSource messageSource) {
        this.themeSelector = themeSelector;
        this.overviewService = overviewService;
        this.exportService = exportService;
        this.messageSource = messageSource;
    }

    /**
     * 渲染用户中心首页。
     *
     * @param done      上一步操作的成功标记,可为空
     * @param failed    上一步操作是否失败,可为空
     * @param request   当前请求,用于识别本机设备
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化用户中心视图名
     */
    @GetMapping("/account")
    public String account(@RequestParam(value = "done", required = false) String done,
                          @RequestParam(value = "failed", required = false) String failed,
                          HttpServletRequest request, Model model, CsrfToken csrfToken) {
        AccountOverview overview = overviewService.overview(IamSessionCookie.read(request));
        model.addAttribute("overview", overview);
        model.addAttribute("realmKey", overview.profile().realmKey());
        model.addAttribute("notice", notice(done));
        model.addAttribute("failed", failed != null);
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("account/account");
    }

    /**
     * 撤销本人对某个应用的授权。
     *
     * @param clientKey 客户端标识
     * @return 重定向回用户中心
     */
    @PostMapping("/account/consents/revoke")
    public String revokeConsent(@RequestParam("clientKey") String clientKey) {
        try {
            overviewService.revokeConsent(clientKey);
            return redirect(DONE_CONSENT_REVOKED);
        } catch (BusinessException e) {
            // 记录明细但不回显:跨域客户端是否存在这类信息不该出现在页面上
            log.warn("撤销授权失败: clientKey={}, reason={}", clientKey, e.getMessage());
            return redirectFailed();
        }
    }

    /**
     * 注销本人指定的登录设备。
     *
     * @param sid     目标会话标识
     * @param request 当前请求,用于识别本机设备
     * @return 重定向回用户中心
     */
    @PostMapping("/account/sessions/revoke")
    public String revokeSession(@RequestParam("sid") String sid, HttpServletRequest request) {
        try {
            overviewService.revokeSession(sid, IamSessionCookie.read(request));
            return redirect(DONE_SESSION_REVOKED);
        } catch (BusinessException e) {
            log.warn("注销设备失败: sid={}, reason={}", sid, e.getMessage());
            return redirectFailed();
        }
    }

    /**
     * 导出本人个人数据(JSON 附件)。
     *
     * @param request 当前请求,用于识别本机设备
     * @return 以附件形式下载的 JSON 快照
     */
    @GetMapping("/account/export")
    public ResponseEntity<Map<String, Object>> export(HttpServletRequest request) {
        String username = CurrentUserHolder.require().getUsername();
        Map<String, Object> document = exportService.export(IamSessionCookie.read(request));
        String filename = "iam-personal-data-" + safeSegment(username) + "-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(document);
    }

    /**
     * 把成功标记翻译成提示文案。
     *
     * @param done 成功标记
     * @return 提示文案;标记为空或无法识别时返回 null
     */
    private String notice(String done) {
        if (done == null || done.isBlank()) {
            return null;
        }
        String key = switch (done) {
            case DONE_CONSENT_REVOKED -> "page.account.notice.consentRevoked";
            case DONE_SESSION_REVOKED -> "page.account.notice.sessionRevoked";
            default -> null;
        };
        return key == null ? null : messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /**
     * 重定向回用户中心并带上成功标记。
     *
     * @param done 成功标记
     * @return 重定向视图名
     */
    private static String redirect(String done) {
        return "redirect:/account?done=" + done;
    }

    /**
     * 重定向回用户中心并带上失败标记。
     *
     * @return 重定向视图名
     */
    private static String redirectFailed() {
        return "redirect:/account?failed=1";
    }

    /**
     * 把登录名清洗为可安全放进文件名的片段。
     *
     * @param value 原始值
     * @return 仅含字母、数字、点、下划线与连字符的片段;清洗后为空时返回 user
     */
    private static String safeSegment(String value) {
        if (value == null) {
            return "user";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "user" : cleaned;
    }
}
