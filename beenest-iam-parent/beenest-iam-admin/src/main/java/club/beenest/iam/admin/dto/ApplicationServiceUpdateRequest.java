package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新应用服务请求。
 * <p>应用编码不可修改;字段为 null 表示不更新该项。</p>
 *
 * @param appName                应用名称
 * @param audiences              访问令牌受众(aud),可多个;空列表表示清空并回落到按应用编码派生
 * @param appType                应用类型
 * @param ssoMode                单点接入方式
 * @param ssoClientPk            关联客户端主键
 * @param iconUrl                图标地址
 * @param entryUrl               入口地址:门户启动时的跳转目标,由应用在入口页自行发起授权
 * @param category               分类
 * @param menuMode               菜单托管模式:IAM 全量托管 / HYBRID 仅一级模块 / APP 应用自管
 * @param authorizationMode      授权模式:IAM_MANAGED / MAPPED / APP_MANAGED
 * @param tokenProfile           令牌收敛档:SCOPED / LEGACY_GLOBAL
 * @param sortOrder              排序号
 * @param visibility             可见性
 * @param requiredPermissionCode 所需权限编码
 * @param enabled                是否启用
 * @param description            说明
 */
public record ApplicationServiceUpdateRequest(
        @Size(max = 200, message = "应用名称长度不能超过 200")
        String appName,

        @Size(max = 5, message = "受众最多 5 个")
        List<@NotBlank(message = "受众不能为空")
             @Size(max = 200, message = "单个受众长度不能超过 200")
             @Pattern(regexp = "[^,]*", message = "受众不能包含英文逗号") String> audiences,

        String appType,

        String ssoMode,

        Long ssoClientPk,

        @Size(max = 500, message = "图标地址长度不能超过 500")
        String iconUrl,

        @Size(max = 500, message = "入口地址长度不能超过 500")
        String entryUrl,

        @Size(max = 50, message = "分类长度不能超过 50")
        String category,

        @Pattern(regexp = "IAM|HYBRID|APP", message = "菜单托管模式只能是 IAM / HYBRID / APP")
        String menuMode,

        @Pattern(regexp = "IAM_MANAGED|MAPPED|APP_MANAGED",
                message = "授权模式只能是 IAM_MANAGED / MAPPED / APP_MANAGED")
        String authorizationMode,

        @Pattern(regexp = "SCOPED|LEGACY_GLOBAL",
                message = "令牌收敛档只能是 SCOPED / LEGACY_GLOBAL")
        String tokenProfile,

        Integer sortOrder,

        String visibility,

        @Size(max = 150, message = "权限编码长度不能超过 150")
        String requiredPermissionCode,

        Boolean enabled,

        @Size(max = 500, message = "说明长度不能超过 500")
        String description
) {
}
