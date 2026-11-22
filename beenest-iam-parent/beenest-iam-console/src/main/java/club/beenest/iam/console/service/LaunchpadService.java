package club.beenest.iam.console.service;

import club.beenest.iam.authz.launch.LaunchPlan;
import club.beenest.iam.console.dto.ConsoleLauncherAppResponse;
import club.beenest.iam.console.dto.ConsoleMenuTreeResponse;

import java.util.List;

/**
 * 应用门户(启动台)服务。
 * <p>应用可见性取全员可见、按角色/用户/组授权、按权限码三类规则的并集。</p>
 */
public interface LaunchpadService {

    /**
     * 当前用户可启动的应用卡片列表。
     *
     * @return 应用卡片列表,已带可进入判定
     */
    List<ConsoleLauncherAppResponse> listApps();

    /**
     * 当前用户在某应用下被授予的菜单树。
     * <p>应用菜单自管(menuMode=APP)时返回空树,由应用按权限码渲染本地菜单。</p>
     *
     * @param appCode 应用编码(应用不可见时按不存在处理)
     * @return 菜单树与菜单版本
     */
    ConsoleMenuTreeResponse menus(String appCode);

    /**
     * 规划应用启动:算出这一次点击该把浏览器送到哪里。
     * <p>只做判定,不产生任何凭据;凭据由应用与授权端点之间完成交换。</p>
     *
     * @param appCode 应用编码
     * @return 启动计划,含目标地址与不可启动的原因
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或对当前用户不可见
     */
    LaunchPlan planLaunch(String appCode);
}
