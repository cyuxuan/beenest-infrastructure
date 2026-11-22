package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.EffectivePermissionResponse;
import club.beenest.iam.admin.dto.PermissionCandidateResponse;
import club.beenest.iam.admin.dto.PermissionOverrideRequest;
import club.beenest.iam.admin.dto.PermissionOverrideResponse;
import club.beenest.iam.admin.dto.PermissionScopeResponse;
import club.beenest.iam.admin.service.UserPermissionService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户级权限治理接口。
 * <p>读取权限诊断需要用户查看权限;写入特批需要单独的用户权限治理权限。
 * 目标用户与权限均按当前身份域校验,跨域主键按不存在处理。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
public class UserPermissionController {

    private final UserPermissionService userPermissionService;

    public UserPermissionController(UserPermissionService userPermissionService) {
        this.userPermissionService = userPermissionService;
    }

    /**
     * 查询用户的权限特批列表(含已过期记录)。
     *
     * @param userId 用户主键
     * @return 特批列表,含权限编码与生效状态,过期记录仅用于追溯
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/permission-overrides")
    @PreAuthorize("hasAnyAuthority('user:view', 'user:grant-permission')")
    public Result<List<PermissionOverrideResponse>> listOverrides(@PathVariable Long userId) {
        return Result.success(userPermissionService.listOverrides(userId));
    }

    /**
     * 查询权限范围:当前域的应用服务列表。
     *
     * @param userId 用户主键
     * @return 当前域应用列表,用于筛选权限命名空间
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/permission-scopes")
    @PreAuthorize("hasAnyAuthority('user:view', 'user:grant-permission')")
    public Result<List<PermissionScopeResponse>> listScopes(@PathVariable Long userId) {
        return Result.success(userPermissionService.listScopes(userId));
    }

    /**
     * 分页查询可授予的权限候选,并返回用户当前特批状态。
     *
     * @param userId       用户主键
     * @param keyword      编码或名称模糊过滤(可选)
     * @param appServiceId 应用命名空间过滤(可选,不传表示全部)
     * @param pageNum      页码
     * @param pageSize     每页条数
     * @return 权限候选分页结果
     * @throws club.beenest.iam.common.exception.BusinessException 用户或应用不存在、跨域
     */
    @GetMapping("/permission-overrides/candidates")
    @PreAuthorize("hasAnyAuthority('user:view', 'user:grant-permission')")
    public Result<PageResult<PermissionCandidateResponse>> pageCandidates(
            @PathVariable Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long appServiceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        return Result.success(userPermissionService.pageCandidates(
                userId, keyword, appServiceId, pageNum, pageSize));
    }

    /**
     * 查询用户有效权限及各来源明细(角色直授、菜单派生、用户特批)。
     *
     * @param userId       用户主键
     * @param appServiceId 应用命名空间过滤(可选,不传表示全部)
     * @return 有效权限明细,含有效角色与来源链路
     * @throws club.beenest.iam.common.exception.BusinessException 用户或应用不存在、跨域
     */
    @GetMapping("/effective-permissions")
    @PreAuthorize("hasAnyAuthority('user:view', 'user:grant-permission')")
    public Result<EffectivePermissionResponse> inspectEffective(
            @PathVariable Long userId,
            @RequestParam(required = false) Long appServiceId) {
        return Result.success(userPermissionService.inspectEffective(userId, appServiceId));
    }

    /**
     * 设置或覆盖用户对某个权限的特批。
     * <p>不允许修改当前登录账号自身的特批;过期时间必须晚于当前时间;
     * 保存后权限代次抬升,特批在下一次认证或新签发令牌时生效。</p>
     *
     * @param userId       用户主键
     * @param permissionId 权限主键
     * @param request      特批请求,含 GRANT/REVOKE 类型、事由与过期时间
     * @return 保存后的特批
     * @throws club.beenest.iam.common.exception.BusinessException 用户或权限不存在、目标为本人或过期时间不合法
     */
    @PutMapping("/permission-overrides/{permissionId}")
    @PreAuthorize("hasAuthority('user:grant-permission')")
    @LogAudit(action = "USER_PERMISSION_OVERRIDE_SET", description = "设置用户权限特批")
    public Result<PermissionOverrideResponse> saveOverride(
            @PathVariable Long userId,
            @PathVariable Long permissionId,
            @Valid @RequestBody PermissionOverrideRequest request) {
        return Result.success(userPermissionService.saveOverride(userId, permissionId, request));
    }

    /**
     * 删除用户对某个权限的特批。
     * <p>不允许删除当前登录账号自身的特批;删除后权限代次抬升。</p>
     *
     * @param userId       用户主键
     * @param permissionId 权限主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在、目标为本人或特批不存在
     */
    @DeleteMapping("/permission-overrides/{permissionId}")
    @PreAuthorize("hasAuthority('user:grant-permission')")
    @LogAudit(action = "USER_PERMISSION_OVERRIDE_DELETE", description = "删除用户权限特批")
    public Result<Void> deleteOverride(@PathVariable Long userId, @PathVariable Long permissionId) {
        userPermissionService.deleteOverride(userId, permissionId);
        return Result.success();
    }
}
