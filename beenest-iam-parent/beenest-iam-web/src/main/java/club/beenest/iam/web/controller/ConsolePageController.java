package club.beenest.iam.web.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理控制台页面控制器。
 * <p>控制台是前端外壳(单页应用),自身不含业务逻辑:页面只渲染骨架并内嵌 CSRF 令牌,
 * 数据全部通过 {@code /api/v1/admin/**} 管理接口获取。权限校验在服务端方法级注解,
 * 前端菜单与按钮的可见性仅是体验层过滤,不构成安全边界。</p>
 * <p>静态资源走 {@code /console-static/**},与登录主题(theme)相互独立,
 * 不随登录页主题切换而变化。</p>
 */
@Controller
public class ConsolePageController {

    /** 控制台入口路径 */
    public static final String CONSOLE_PATH = "/console";

    /**
     * 渲染控制台外壳。
     * <p>CSRF 令牌内嵌到 meta,前端读写类请求时回传 {@code X-XSRF-TOKEN} 头;
     * 首次进入时 Cookie 与页面同批下发,内嵌 meta 不依赖 Cookie 的就绪时序。</p>
     * <p>History 路由下所有子路径({@code /console/users} 等)回退到同一外壳,
     * 刷新与书签直达由前端路由接管。</p>
     *
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 控制台外壳视图名
     */
    @GetMapping({CONSOLE_PATH, CONSOLE_PATH + "/", CONSOLE_PATH + "/**"})
    public String console(Model model, CsrfToken csrfToken) {
        model.addAttribute("_csrf", csrfToken);
        return "console/index";
    }

    /**
     * 根路径重定向到应用门户:登录后的默认落地页,管理控制台为管理员的二级入口。
     *
     * @return 门户重定向地址
     */
    @GetMapping("/")
    public String root() {
        return "redirect:" + PortalPageController.PORTAL_PATH;
    }

    /**
     * 历史路径兼容:{@code /admin} 归一为控制台入口。
     * <p>管理接口位于 {@code /api/v1/admin/**},页面路径不复用该前缀以免混淆。</p>
     *
     * @return 控制台重定向地址
     */
    @GetMapping("/admin")
    public String adminEntry() {
        return "redirect:" + CONSOLE_PATH;
    }
}
