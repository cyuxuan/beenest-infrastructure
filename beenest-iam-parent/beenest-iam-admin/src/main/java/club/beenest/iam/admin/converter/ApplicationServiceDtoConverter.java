package club.beenest.iam.admin.converter;

import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.admin.dto.ApplicationServiceResponse;
import club.beenest.iam.admin.dto.ApplicationServiceUpdateRequest;
import club.beenest.iam.persistence.entity.ApplicationService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 应用服务管理端对象转换器。
 * <p>负责请求、实体、响应之间的字段搬运并写入缺省值;接入配置的一致性校验在服务层完成。</p>
 */
@Component
public class ApplicationServiceDtoConverter {

    /** 应用类型:Web 应用 */
    private static final String TYPE_WEB = "WEB";
    /** 单点接入方式:OIDC */
    private static final String MODE_OIDC = "OIDC";
    /** 可见性:按权限 */
    private static final String VISIBILITY_PERMISSION = "PERMISSION";
    /** 菜单托管模式默认:一级模块由 IAM 管,深层菜单由应用自管 */
    private static final String MODE_MENU_HYBRID = "HYBRID";
    /** 授权模式默认:IAM 托管角色与权限 */
    private static final String MODE_AUTHZ_IAM = "IAM_MANAGED";
    /** 令牌收敛档默认:新应用按应用裁剪 */
    private static final String TOKEN_PROFILE_SCOPED = "SCOPED";

    /**
     * 应用服务实体转响应。
     *
     * @param app 应用服务实体,可为 null
     * @return 应用服务响应,入参为 null 时返回 null
     */
    public ApplicationServiceResponse toResponse(ApplicationService app) {
        if (app == null) {
            return null;
        }
        return new ApplicationServiceResponse(
                app.getId(),
                app.getRealmId(),
                app.getAppCode(),
                splitAudiences(app.getAudience()),
                app.getAppName(),
                app.getAppType(),
                app.getSsoMode(),
                app.getSsoClientPk(),
                app.getIconUrl(),
                app.getEntryUrl(),
                app.getCategory(),
                app.getMenuMode(),
                app.getAuthorizationMode(),
                app.getTokenProfile(),
                app.getSortOrder(),
                app.getVisibility(),
                app.getRequiredPermissionCode(),
                app.getEnabled(),
                app.getDescription(),
                app.getCreatedTime(),
                app.getUpdatedTime());
    }

    /**
     * 新建请求转实体。
     * <p>类型缺省 WEB、接入方式缺省 OIDC、可见性缺省 PERMISSION、菜单托管缺省 HYBRID、
     * 授权模式缺省 IAM_MANAGED、令牌档缺省 SCOPED;新建应用一律启用。</p>
     *
     * @param request 新建请求
     * @param realmId 身份域主键
     * @return 应用服务实体
     */
    public ApplicationService toEntity(ApplicationServiceCreateRequest request, Long realmId) {
        ApplicationService app = new ApplicationService();
        app.setRealmId(realmId);
        app.setAppCode(request.appCode());
        app.setAudience(joinAudiences(request.audiences()));
        app.setAppName(request.appName());
        app.setAppType(defaultIfBlank(request.appType(), TYPE_WEB).toUpperCase());
        app.setSsoMode(defaultIfBlank(request.ssoMode(), MODE_OIDC).toUpperCase());
        app.setSsoClientPk(request.ssoClientPk());
        app.setIconUrl(request.iconUrl());
        app.setEntryUrl(request.entryUrl());
        app.setCategory(request.category());
        app.setMenuMode(defaultIfBlank(request.menuMode(), MODE_MENU_HYBRID).toUpperCase());
        app.setAuthorizationMode(defaultIfBlank(request.authorizationMode(), MODE_AUTHZ_IAM).toUpperCase());
        app.setTokenProfile(defaultIfBlank(request.tokenProfile(), TOKEN_PROFILE_SCOPED).toUpperCase());
        app.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        app.setVisibility(defaultIfBlank(request.visibility(), VISIBILITY_PERMISSION).toUpperCase());
        app.setRequiredPermissionCode(request.requiredPermissionCode());
        app.setEnabled(true);
        app.setDescription(request.description());
        return app;
    }

    /**
     * 将更新请求中的非空字段写入实体,应用编码不可修改,枚举字段统一转大写。
     *
     * @param app     库中应用服务实体
     * @param request 更新请求
     */
    public void applyUpdate(ApplicationService app, ApplicationServiceUpdateRequest request) {
        if (request.appName() != null) {
            app.setAppName(request.appName());
        }
        if (request.audiences() != null) {
            app.setAudience(joinAudiences(request.audiences()));
        }
        if (request.appType() != null) {
            app.setAppType(request.appType().toUpperCase());
        }
        if (request.ssoMode() != null) {
            app.setSsoMode(request.ssoMode().toUpperCase());
        }
        if (request.ssoClientPk() != null) {
            app.setSsoClientPk(request.ssoClientPk());
        }
        if (request.iconUrl() != null) {
            app.setIconUrl(request.iconUrl());
        }
        if (request.entryUrl() != null) {
            app.setEntryUrl(request.entryUrl());
        }
        if (request.category() != null) {
            app.setCategory(request.category());
        }
        if (request.menuMode() != null) {
            app.setMenuMode(request.menuMode().toUpperCase());
        }
        if (request.authorizationMode() != null) {
            app.setAuthorizationMode(request.authorizationMode().toUpperCase());
        }
        if (request.tokenProfile() != null) {
            app.setTokenProfile(request.tokenProfile().toUpperCase());
        }
        if (request.sortOrder() != null) {
            app.setSortOrder(request.sortOrder());
        }
        if (request.visibility() != null) {
            app.setVisibility(request.visibility().toUpperCase());
        }
        if (request.requiredPermissionCode() != null) {
            app.setRequiredPermissionCode(request.requiredPermissionCode());
        }
        if (request.enabled() != null) {
            app.setEnabled(request.enabled());
        }
        if (request.description() != null) {
            app.setDescription(request.description());
        }
    }

    /**
     * 空白值取默认值。
     *
     * @param value    原始值
     * @param fallback 默认值
     * @return 原始值或默认值
     */
    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 受众列表拼成库内单串,以英文逗号分隔。
     * <p>空列表拼成空串而非 null:动态 SQL 会跳过 null 列,管理员清空受众的操作就落不了库。</p>
     *
     * @param audiences 受众列表,可为 null
     * @return 逗号分隔的受众;入参为 null 时返回 null(表示本次不改动)
     */
    private static String joinAudiences(List<String> audiences) {
        if (audiences == null) {
            return null;
        }
        return audiences.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .collect(Collectors.joining(","));
    }

    /**
     * 库内单串拆成受众列表。
     *
     * @param audience 逗号分隔的受众,可为空
     * @return 去空白、去重后的受众列表;入参为空时返回空列表
     */
    private static List<String> splitAudiences(String audience) {
        if (audience == null || audience.isBlank()) {
            return List.of();
        }
        return Arrays.stream(audience.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
