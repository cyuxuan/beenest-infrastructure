package club.beenest.iam.console.dto;

/**
 * 启动台应用卡片响应。
 * <p>只暴露渲染卡片所需的字段,不含跳转地址:跳转统一走
 * {@code GET /portal/launch/{appCode}},由服务端在点击时重新判定。</p>
 *
 * @param appCode    应用编码
 * @param appName    应用名称
 * @param appType    应用类型
 * @param ssoMode    单点接入方式
 * @param iconUrl    图标地址
 * @param category   分类
 * @param sortOrder  排序号
 * @param description 说明
 * @param menuMode   菜单托管模式:IAM / HYBRID / APP
 * @param launchable 是否可进入:缺入口地址或应用已停用时为 false
 * @param launchHint 不可进入的原因,可直接展示给用户;可进入时为 null
 */
public record ConsoleLauncherAppResponse(
        String appCode,
        String appName,
        String appType,
        String ssoMode,
        String iconUrl,
        String category,
        Integer sortOrder,
        String description,
        String menuMode,
        boolean launchable,
        String launchHint
) {
}
