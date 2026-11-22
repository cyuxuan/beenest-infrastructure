package club.beenest.iam.console.controller;

import club.beenest.iam.common.result.Result;
import club.beenest.iam.console.dto.ConsoleLauncherAppResponse;
import club.beenest.iam.console.dto.ConsoleMenuTreeResponse;
import club.beenest.iam.console.service.LaunchpadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用门户(启动台)接口。
 * <p>提供有权访问的应用卡片与应用菜单树;菜单自管的应用返回空树,改用权限码。
 * 跳转不在这里:导航走 {@link PortalLaunchController} 的 302 端点。</p>
 */
@RestController
@RequestMapping("/api/v1/launchpad")
public class LaunchpadController {

    private final LaunchpadService launchpadService;

    public LaunchpadController(LaunchpadService launchpadService) {
        this.launchpadService = launchpadService;
    }

    /**
     * 当前用户可启动的应用列表。
     *
     * @return 应用卡片列表,已完成可进入判定
     */
    @GetMapping("/apps")
    public Result<List<ConsoleLauncherAppResponse>> apps() {
        return Result.success(launchpadService.listApps());
    }

    /**
     * 当前用户在指定应用下被授予的菜单树。
     * <p>只要求已登录且应用对当前用户可见,不校验管理端权限码。</p>
     *
     * @param appCode 应用编码
     * @return 菜单树;应用自管菜单时 menus 为空
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或对当前用户不可见
     */
    @GetMapping("/apps/{appCode}/menus")
    public Result<ConsoleMenuTreeResponse> menus(@PathVariable String appCode) {
        return Result.success(launchpadService.menus(appCode));
    }
}
