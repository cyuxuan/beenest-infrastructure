package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AppBackfillImportRequest;
import club.beenest.iam.admin.dto.AppBackfillImportResponse;
import club.beenest.iam.admin.dto.AppBackfillReportResponse;
import club.beenest.iam.admin.service.AppBackfillService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用权限回填接口(存量应用切换 SCOPED 令牌档的前置工具)。
 * <p>报告对应用管理员只读开放;导入会改变平台资源归属,要求同时具备应用管理权与
 * {@code app-service:backfill} 权限。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/apps/{appId}/backfill")
@PreAuthorize("@appAuthz.canManage(#appId)")
public class AppBackfillController {

    private final AppBackfillService appBackfillService;

    public AppBackfillController(AppBackfillService appBackfillService) {
        this.appBackfillService = appBackfillService;
    }

    /**
     * 查询应用的回填报告。
     *
     * @param appId 应用主键
     * @return 切换 SCOPED 后缺失的权限与菜单清单
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或不属于当前身份域
     */
    @GetMapping("/report")
    public Result<AppBackfillReportResponse> report(@PathVariable Long appId) {
        return Result.success(appBackfillService.report(appId));
    }

    /**
     * 执行应用权限回填。
     *
     * @param appId   应用主键
     * @param request 回填请求,包含权限与菜单主键及策略(MOVE/COPY)
     * @return 回填执行结果
     * @throws club.beenest.iam.common.exception.BusinessException 权限或菜单缺失,或不属于当前身份域
     */
    @PostMapping("/import")
    @PreAuthorize("@appAuthz.canManage(#appId) and hasAuthority('app-service:backfill')")
    @LogAudit(action = "APP_BACKFILL_IMPORT", description = "应用权限回填")
    public Result<AppBackfillImportResponse> importBackfill(
            @PathVariable Long appId,
            @Valid @RequestBody AppBackfillImportRequest request) {
        return Result.success(appBackfillService.importBackfill(appId, request));
    }
}
