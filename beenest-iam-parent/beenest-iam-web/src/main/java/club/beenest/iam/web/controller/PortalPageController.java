package club.beenest.iam.web.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 用户门户(应用启动台)页面控制器。
 * <p>门户是 IAM 的用户面:登录后落地,展示当前用户有权访问的应用,点击卡片完成静默单点跳转。
 * 它与管理控制台是两套独立的前端外壳:门户只需"已登录",不要求任何管理权限;
 * 静态资源走 {@code /portal-static/**},与控制台、登录主题互不干扰。</p>
 */
@Controller
public class PortalPageController {

    /** 门户入口路径 */
    public static final String PORTAL_PATH = "/portal";

    /**
     * 渲染门户外壳。
     * <p>CSRF 令牌内嵌到 meta:启动接口是 POST,需要回传令牌。</p>
     *
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 门户外壳视图名
     */
    @GetMapping({PORTAL_PATH, PORTAL_PATH + "/"})
    public String portal(Model model, CsrfToken csrfToken) {
        model.addAttribute("_csrf", csrfToken);
        return "portal/index";
    }
}
