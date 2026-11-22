package club.beenest.iam.console.controller;

import club.beenest.iam.authz.launch.LaunchPlan;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.console.service.LaunchpadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 门户启动跳转端点:把点击卡片变成一次 302。
 * <p>跳转目标在点击那一刻由服务端判定,不返回地址给前端再让它自己跳:
 * 少一个往返,也不会因为列表与点击之间改了配置而把人送到失效地址。
 * 未登录时走页面链的登录跳转,不会在窗口里留下 401 JSON。</p>
 * <p>本端点不产生凭据,目标就是应用入口地址。</p>
 */
@Controller
@RequestMapping("/portal/launch")
public class PortalLaunchController {

    /** 门户首页:不可启动时把用户送回去并附原因 */
    private static final String PORTAL_PATH = "/portal";
    /** 门户页读取的启动失败参数名 */
    private static final String ERROR_PARAM = "launchError";
    /** 门户页读取的失败应用编码参数名 */
    private static final String APP_PARAM = "launchApp";

    private final LaunchpadService launchpadService;

    public PortalLaunchController(LaunchpadService launchpadService) {
        this.launchpadService = launchpadService;
    }

    /**
     * 启动应用:302 到启动计划给出的目标地址。
     * <p>不可启动时回到门户并带上原因,用户点的是卡片,给他一个错误码没有意义。</p>
     * <p>Location 以字符串写入响应头,不经 URI 解析:目标地址由管理员录入,
     * 可能带未经编码的字符。</p>
     *
     * @param appCode 应用编码
     * @return 302 响应,Location 指向启动目标或带回原因的门户页
     */
    @GetMapping("/{appCode}")
    @LogAudit(action = "APP_LAUNCH", description = "门户启动应用 {appCode}")
    public ResponseEntity<Void> launch(@PathVariable String appCode) {
        LaunchPlan plan = launchpadService.planLaunch(appCode);
        String location = plan.blocked()
                ? PORTAL_PATH + "?" + ERROR_PARAM + "=" + encode(plan.hint())
                        + "&" + APP_PARAM + "=" + encode(appCode)
                : plan.target();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, location);
        // 启动地址每次重新判定,不允许缓存
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码后的值;入参为 null 时返回空串
     */
    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
