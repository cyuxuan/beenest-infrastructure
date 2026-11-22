package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AppAdminCreateRequest;
import club.beenest.iam.admin.dto.AppAdminResponse;
import club.beenest.iam.admin.dto.ManagedAppResponse;
import club.beenest.iam.admin.service.AppAdminService;
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
 * 应用管理员接口。
 * <p>管理员名单仅持有 {@code app-service:assign} 的全局管理员可维护;应用管理员可读取
 * 自己管理范围内的应用与管理员名单,控制台按 {@code /managed} 结果收窄导航与数据范围。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/apps")
public class AppAdminController {

    private final AppAdminService appAdminService;

    public AppAdminController(AppAdminService appAdminService) {
        this.appAdminService = appAdminService;
    }

    /**
     * 当前用户可管理的应用范围。
     *
     * @return 可管理应用列表;全局管理员返回全量
     */
    @GetMapping("/managed")
    @PreAuthorize("isAuthenticated()")
    public Result<List<ManagedAppResponse>> managedApps() {
        return Result.success(appAdminService.listManagedApps());
    }

    /**
     * 查询应用的管理员名单。
     *
     * @param appId 应用主键
     * @return 管理员名单(用户与用户组)
     */
    @GetMapping("/{appId}/admins")
    @PreAuthorize("hasAuthority('app-service:view') or @appAuthz.canManage(#appId)")
    public Result<List<AppAdminResponse>> listAdmins(@PathVariable Long appId) {
        return Result.success(appAdminService.listAdmins(appId));
    }

    /**
     * 新增应用管理员(用户或用户组)。
     *
     * @param appId   应用主键
     * @param request 新增请求,包含主体类型(USER/GROUP)与主体主键
     * @return 新增的管理员记录
     * @throws club.beenest.iam.common.exception.BusinessException 主体已是管理员,或主体不在当前身份域
     */
    @PostMapping("/{appId}/admins")
    @PreAuthorize("hasAuthority('app-service:assign')")
    @LogAudit(action = "APP_ADMIN_CREATE", description = "新增应用管理员")
    public Result<AppAdminResponse> addAdmin(@PathVariable Long appId,
                                             @Valid @RequestBody AppAdminCreateRequest request) {
        return Result.success(appAdminService.addAdmin(appId, request));
    }

    /**
     * 移除应用管理员。
     *
     * @param appId   应用主键
     * @param adminId 管理员关系主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 管理员关系不存在
     */
    @DeleteMapping("/{appId}/admins/{adminId}")
    @PreAuthorize("hasAuthority('app-service:assign')")
    @LogAudit(action = "APP_ADMIN_DELETE", description = "移除应用管理员")
    public Result<Void> removeAdmin(@PathVariable Long appId, @PathVariable Long adminId) {
        appAdminService.removeAdmin(appId, adminId);
        return Result.success();
    }
}
