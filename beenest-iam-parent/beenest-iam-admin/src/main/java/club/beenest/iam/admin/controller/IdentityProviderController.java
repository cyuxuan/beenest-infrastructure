package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.IdpMapperCreateRequest;
import club.beenest.iam.admin.dto.IdpMapperResponse;
import club.beenest.iam.admin.dto.IdentityProviderCreateRequest;
import club.beenest.iam.admin.dto.IdentityProviderResponse;
import club.beenest.iam.admin.dto.IdentityProviderUpdateRequest;
import club.beenest.iam.admin.service.IdentityProviderAdminService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.core.spi.federation.ProviderTypeInfo;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 身份提供方管理接口(联邦登录配置)。
 * <p>配置中的敏感值只写不读,响应仅返回配置键名。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/identity-providers")
public class IdentityProviderController {

    private final IdentityProviderAdminService identityProviderAdminService;
    private final IdentityProviderRegistry identityProviderRegistry;

    public IdentityProviderController(IdentityProviderAdminService identityProviderAdminService,
                                      IdentityProviderRegistry identityProviderRegistry) {
        this.identityProviderAdminService = identityProviderAdminService;
        this.identityProviderRegistry = identityProviderRegistry;
    }

    /**
     * 查询域内全部提供方。
     *
     * @return 当前域的提供方列表,配置项仅含键名
     */
    @GetMapping
    @PreAuthorize("hasAuthority('idp:view')")
    public Result<List<IdentityProviderResponse>> list() {
        return Result.success(identityProviderAdminService.list());
    }

    /**
     * 查询容器内已注册的提供方类型及其配置项声明。
     * <p>管理端据此渲染类型下拉与配置表单;可用类型由已注册的扩展包决定。</p>
     *
     * @return 提供方类型元数据列表
     */
    @GetMapping("/provider-types")
    @PreAuthorize("hasAuthority('idp:view')")
    public Result<List<ProviderTypeInfo>> providerTypes() {
        return Result.success(identityProviderRegistry.listProviderTypes());
    }

    /**
     * 查询单个提供方。
     *
     * @param id 提供方主键
     * @return 提供方配置,敏感项不回显
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('idp:view')")
    public Result<IdentityProviderResponse> get(@PathVariable Long id) {
        return Result.success(identityProviderAdminService.get(id));
    }

    /**
     * 新建提供方。
     *
     * @param request 新建请求,包含别名、类型与配置项;别名域内唯一,启用状态必须配齐该类型的必填配置项
     * @return 新建的提供方
     * @throws club.beenest.iam.common.exception.BusinessException 别名重复或启用时必填配置项缺失
     */
    @PostMapping
    @PreAuthorize("hasAuthority('idp:create')")
    @LogAudit(action = "IDP_CREATE", description = "新建身份提供方")
    public Result<IdentityProviderResponse> create(@Valid @RequestBody IdentityProviderCreateRequest request) {
        return Result.success(identityProviderAdminService.create(request));
    }

    /**
     * 更新提供方。
     *
     * @param id      提供方主键
     * @param request 更新请求;机密项留空表示保留库中原值,非机密项整体覆盖
     * @return 更新后的提供方
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在或合并后的配置不满足必填项
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('idp:update')")
    @LogAudit(action = "IDP_UPDATE", description = "更新身份提供方")
    public Result<IdentityProviderResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody IdentityProviderUpdateRequest request) {
        return Result.success(identityProviderAdminService.update(id, request));
    }

    /**
     * 删除提供方(解绑外部身份并级联清理属性映射器)。
     *
     * @param id 提供方主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('idp:delete')")
    @LogAudit(action = "IDP_DELETE", description = "删除身份提供方")
    public Result<Void> delete(@PathVariable Long id) {
        identityProviderAdminService.delete(id);
        return Result.success();
    }

    /**
     * 查询提供方的属性映射器。
     *
     * @param id 提供方主键
     * @return 属性映射器列表
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在或不属于当前域
     */
    @GetMapping("/{id}/mappers")
    @PreAuthorize("hasAuthority('idp-mapper:view')")
    public Result<List<IdpMapperResponse>> listMappers(@PathVariable Long id) {
        return Result.success(identityProviderAdminService.listMappers(id));
    }

    /**
     * 新建属性映射器。
     *
     * @param id      提供方主键
     * @param request 新建请求,映射类型缺省为 ATTRIBUTE
     * @return 新建的映射器
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在或不属于当前域
     */
    @PostMapping("/{id}/mappers")
    @PreAuthorize("hasAuthority('idp-mapper:create')")
    @LogAudit(action = "IDP_MAPPER_CREATE", description = "新建 IdP 属性映射器")
    public Result<IdpMapperResponse> createMapper(@PathVariable Long id,
                                                  @Valid @RequestBody IdpMapperCreateRequest request) {
        return Result.success(identityProviderAdminService.createMapper(id, request));
    }

    /**
     * 删除属性映射器。
     *
     * @param id       提供方主键
     * @param mapperId 映射器主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 提供方不存在,或映射器不属于该提供方
     */
    @DeleteMapping("/{id}/mappers/{mapperId}")
    @PreAuthorize("hasAuthority('idp-mapper:delete')")
    @LogAudit(action = "IDP_MAPPER_DELETE", description = "删除 IdP 属性映射器")
    public Result<Void> deleteMapper(@PathVariable Long id, @PathVariable Long mapperId) {
        identityProviderAdminService.deleteMapper(id, mapperId);
        return Result.success();
    }
}
