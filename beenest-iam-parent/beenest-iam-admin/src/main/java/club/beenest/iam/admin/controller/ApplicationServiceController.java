package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AppServiceGrantResponse;
import club.beenest.iam.admin.dto.AppServiceGrantUpdateRequest;
import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.admin.dto.ApplicationServiceResponse;
import club.beenest.iam.admin.dto.ApplicationServiceUpdateRequest;
import club.beenest.iam.admin.service.ApplicationServiceService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用服务管理接口。
 * <p>应用服务是应用门户的卡片数据源;操作范围限定在调用者所属身份域,OIDC 接入必须
 * 绑定本域客户端,可见性为 PERMISSION 时必须指定所需权限编码。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/application-services")
public class ApplicationServiceController {

    private final ApplicationServiceService applicationServiceService;

    public ApplicationServiceController(ApplicationServiceService applicationServiceService) {
        this.applicationServiceService = applicationServiceService;
    }

    /**
     * 分页查询应用服务。
     *
     * @param appCode           应用编码(模糊匹配,可空)
     * @param appName           应用名称(模糊匹配,可空)
     * @param appType           应用类型(精确匹配,可空)
     * @param category          分类(精确匹配,可空)
     * @param visibility        可见性(精确匹配,可空)
     * @param authorizationMode 授权模式(精确匹配,可空)
     * @param enabled           启用状态(精确匹配,可空)
     * @param pageNum           页码
     * @param pageSize          每页条数
     * @return 应用服务分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('app-service:view')")
    public Result<PageResult<ApplicationServiceResponse>> page(
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) String appName,
            @RequestParam(required = false) String appType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String authorizationMode,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(applicationServiceService.page(appCode, appName, appType, category,
                visibility, authorizationMode, enabled, pageNum, pageSize));
    }

    /**
     * 查询单个应用服务。
     *
     * @param id 应用服务主键
     * @return 应用服务详情
     * @throws club.beenest.iam.common.exception.BusinessException 应用服务不存在或不属于当前身份域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app-service:view')")
    public Result<ApplicationServiceResponse> get(@PathVariable Long id) {
        return Result.success(applicationServiceService.get(id));
    }

    /**
     * 新建应用服务。
     *
     * @param request 新建请求,包含应用编码、接入方式、可见性与 OIDC 客户端主键
     * @return 新建的应用服务
     * @throws club.beenest.iam.common.exception.BusinessException 应用编码已存在、客户端无效或缺少所需权限编码
     */
    @PostMapping
    @PreAuthorize("hasAuthority('app-service:create')")
    @LogAudit(action = "APP_SERVICE_CREATE", description = "新建应用服务")
    public Result<ApplicationServiceResponse> create(@Valid @RequestBody ApplicationServiceCreateRequest request) {
        return Result.success(applicationServiceService.create(request));
    }

    /**
     * 更新应用服务。
     *
     * @param id      应用服务主键
     * @param request 更新请求,仅写入非空字段(应用编码不可修改)
     * @return 更新后的应用服务
     * @throws club.beenest.iam.common.exception.BusinessException 应用服务不存在,或绑定关系不合法
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app-service:update')")
    @LogAudit(action = "APP_SERVICE_UPDATE", description = "更新应用服务")
    public Result<ApplicationServiceResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody ApplicationServiceUpdateRequest request) {
        return Result.success(applicationServiceService.update(id, request));
    }

    /**
     * 删除应用服务(逻辑删除)。
     *
     * @param id 应用服务主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 应用服务不存在或不属于当前身份域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app-service:delete')")
    @LogAudit(action = "APP_SERVICE_DELETE", description = "删除应用服务")
    public Result<Void> delete(@PathVariable Long id) {
        applicationServiceService.delete(id);
        return Result.success();
    }

    /**
     * 查询应用的授权名单。
     *
     * @param id          应用服务主键
     * @param subjectType 主体类型:USER/ROLE/GROUP
     * @return 授权名单
     * @throws club.beenest.iam.common.exception.BusinessException 主体类型非法
     */
    @GetMapping("/{id}/grants")
    @PreAuthorize("hasAuthority('app-service:view')")
    public Result<List<AppServiceGrantResponse>> listGrants(
            @PathVariable Long id,
            @RequestParam(defaultValue = "ROLE") String subjectType) {
        return Result.success(applicationServiceService.listGrants(id, subjectType));
    }

    /**
     * 整体覆盖应用在某一主体类型下的授权名单。
     *
     * @param id          应用服务主键
     * @param subjectType 主体类型:USER/ROLE/GROUP
     * @param request     授权请求,包含目标主体主键集合,空集合表示清空
     * @return 覆盖后的授权名单
     * @throws club.beenest.iam.common.exception.BusinessException 主体类型非法或应用服务不存在
     */
    @PutMapping("/{id}/grants")
    @PreAuthorize("hasAuthority('app-service:assign')")
    @LogAudit(action = "APP_SERVICE_ASSIGN", description = "维护应用授权名单")
    public Result<List<AppServiceGrantResponse>> replaceGrants(
            @PathVariable Long id,
            @RequestParam(defaultValue = "ROLE") String subjectType,
            @Valid @RequestBody AppServiceGrantUpdateRequest request) {
        return Result.success(applicationServiceService.replaceGrants(id, subjectType, request.subjectIds()));
    }
}
