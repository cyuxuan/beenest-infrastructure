package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AppRoleMappingCreateRequest;
import club.beenest.iam.admin.dto.AppRoleMappingResponse;
import club.beenest.iam.admin.service.AppRoleMappingService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用角色映射接口(MAPPED 应用)。
 * <p>映射把 IAM 的用户组/域角色转换为应用自有角色码,令牌只输出目标角色码;
 * 变更在下次认证时生效,需要立即生效时对相关账号执行强制下线。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/apps")
public class AppRoleMappingController {

    private final AppRoleMappingService appRoleMappingService;

    public AppRoleMappingController(AppRoleMappingService appRoleMappingService) {
        this.appRoleMappingService = appRoleMappingService;
    }

    /**
     * 查询应用的角色映射列表。
     *
     * @param appId 应用主键
     * @return 映射列表(含停用项)
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或不属于当前身份域
     */
    @GetMapping("/{appId}/role-mappings")
    @PreAuthorize("hasAuthority('app-service:view') or @appAuthz.canManage(#appId)")
    public Result<List<AppRoleMappingResponse>> list(@PathVariable Long appId) {
        return Result.success(appRoleMappingService.list(appId));
    }

    /**
     * 新增角色映射。
     *
     * @param appId   应用主键
     * @param request 新增请求,包含来源类型(GROUP/REALM_ROLE)、来源主键与目标角色码
     * @return 新增的映射记录
     * @throws club.beenest.iam.common.exception.BusinessException 映射已存在,或来源不在当前身份域
     */
    @PostMapping("/{appId}/role-mappings")
    @PreAuthorize("hasAuthority('app-service:update') or @appAuthz.canManage(#appId)")
    @LogAudit(action = "APP_ROLE_MAPPING_CREATE", description = "新增应用角色映射")
    public Result<AppRoleMappingResponse> create(@PathVariable Long appId,
                                                 @Valid @RequestBody AppRoleMappingCreateRequest request) {
        return Result.success(appRoleMappingService.create(appId, request));
    }

    /**
     * 删除角色映射。
     *
     * @param appId     应用主键
     * @param mappingId 映射主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 映射不存在
     */
    @DeleteMapping("/{appId}/role-mappings/{mappingId}")
    @PreAuthorize("hasAuthority('app-service:update') or @appAuthz.canManage(#appId)")
    @LogAudit(action = "APP_ROLE_MAPPING_DELETE", description = "删除应用角色映射")
    public Result<Void> delete(@PathVariable Long appId, @PathVariable Long mappingId) {
        appRoleMappingService.delete(appId, mappingId);
        return Result.success();
    }
}
