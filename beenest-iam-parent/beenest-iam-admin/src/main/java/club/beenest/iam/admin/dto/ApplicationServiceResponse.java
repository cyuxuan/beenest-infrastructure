package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用服务响应。
 *
 * @param id                    主键
 * @param realmId               所属域
 * @param appCode               应用编码
 * @param audiences             访问令牌受众(aud)列表;空表示按 urn:iam:app:{appCode} 派生
 * @param appName               应用名称
 * @param appType               应用类型
 * @param ssoMode               单点接入方式
 * @param ssoClientPk           关联客户端主键
 * @param iconUrl               图标地址
 * @param entryUrl              入口地址:门户启动时的跳转目标
 * @param category              分类
 * @param menuMode              菜单托管模式:IAM / HYBRID / APP
 * @param authorizationMode     授权模式:IAM_MANAGED / MAPPED / APP_MANAGED
 * @param tokenProfile          令牌收敛档:SCOPED / LEGACY_GLOBAL
 * @param sortOrder             排序号
 * @param visibility            可见性
 * @param requiredPermissionCode 所需权限编码
 * @param enabled               是否启用
 * @param description           说明
 * @param createdTime           创建时间
 * @param updatedTime           更新时间
 */
public record ApplicationServiceResponse(
        Long id,
        Long realmId,
        String appCode,
        List<String> audiences,
        String appName,
        String appType,
        String ssoMode,
        Long ssoClientPk,
        String iconUrl,
        String entryUrl,
        String category,
        String menuMode,
        String authorizationMode,
        String tokenProfile,
        Integer sortOrder,
        String visibility,
        String requiredPermissionCode,
        Boolean enabled,
        String description,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
