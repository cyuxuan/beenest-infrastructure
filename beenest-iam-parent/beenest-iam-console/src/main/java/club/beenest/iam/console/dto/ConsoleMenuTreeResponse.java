package club.beenest.iam.console.dto;

import java.util.List;

/**
 * 应用菜单下发响应。
 *
 * @param appCode 应用编码
 * @param mode    菜单托管模式:IAM 全量托管 / HYBRID 一级模块 / APP 应用自管(此时 menus 为空)
 * @param version 菜单版本(最大更新时间),供应用侧缓存失效
 * @param menus   菜单树;APP 模式下为空,应用改按权限码渲染本地菜单
 */
public record ConsoleMenuTreeResponse(
        String appCode,
        String mode,
        String version,
        List<ConsoleMenuNodeResponse> menus
) {
}
