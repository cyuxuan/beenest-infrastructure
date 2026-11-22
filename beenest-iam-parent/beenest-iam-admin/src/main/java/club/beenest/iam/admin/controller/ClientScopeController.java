package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.ClientScopeCreateRequest;
import club.beenest.iam.admin.dto.ClientScopeResponse;
import club.beenest.iam.admin.dto.ClientScopeUpdateRequest;
import club.beenest.iam.admin.service.ClientScopeService;
import club.beenest.iam.common.annotation.LogAudit;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户端作用域管理接口。
 * <p>作用域限定在当前身份域且作用域键域内唯一;分配作用域时客户端与作用域都必须属于当前域。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/client-scopes")
public class ClientScopeController {

    private final ClientScopeService clientScopeService;

    public ClientScopeController(ClientScopeService clientScopeService) {
        this.clientScopeService = clientScopeService;
    }

    /**
     * 查询域内全部作用域。
     *
     * @return 当前域的作用域列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('client-scope:view')")
    public Result<List<ClientScopeResponse>> list() {
        return Result.success(clientScopeService.list());
    }

    /**
     * 查询单个作用域。
     *
     * @param id 作用域主键
     * @return 作用域详情
     * @throws club.beenest.iam.common.exception.BusinessException 作用域不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('client-scope:view')")
    public Result<ClientScopeResponse> get(@PathVariable Long id) {
        return Result.success(clientScopeService.get(id));
    }

    /**
     * 新建作用域。
     *
     * @param request 新建请求,包含作用域键与名称;作用域键域内唯一
     * @return 新建的作用域
     * @throws club.beenest.iam.common.exception.BusinessException 作用域键在域内已存在
     */
    @PostMapping
    @PreAuthorize("hasAuthority('client-scope:create')")
    @LogAudit(action = "CLIENT_SCOPE_CREATE", description = "新建客户端作用域")
    public Result<ClientScopeResponse> create(@Valid @RequestBody ClientScopeCreateRequest request) {
        return Result.success(clientScopeService.create(request));
    }

    /**
     * 更新作用域。
     *
     * @param id      作用域主键
     * @param request 更新请求,仅覆盖显式给出的字段
     * @return 更新后的作用域
     * @throws club.beenest.iam.common.exception.BusinessException 作用域不存在或不属于当前域
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('client-scope:update')")
    @LogAudit(action = "CLIENT_SCOPE_UPDATE", description = "更新客户端作用域")
    public Result<ClientScopeResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody ClientScopeUpdateRequest request) {
        return Result.success(clientScopeService.update(id, request));
    }

    /**
     * 删除作用域(级联清理客户端关联)。
     *
     * @param id 作用域主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 作用域不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('client-scope:delete')")
    @LogAudit(action = "CLIENT_SCOPE_DELETE", description = "删除客户端作用域")
    public Result<Void> delete(@PathVariable Long id) {
        clientScopeService.delete(id);
        return Result.success();
    }

    /**
     * 查询客户端已分配的作用域。
     *
     * @param clientId 客户端主键
     * @return 已分配的作用域主键列表
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在或不属于当前域
     */
    @GetMapping("/clients/{clientId}")
    @PreAuthorize("hasAuthority('client-scope:view')")
    public Result<List<Long>> listClientScopes(@PathVariable Long clientId) {
        return Result.success(clientScopeService.listClientScopeIds(clientId));
    }

    /**
     * 覆盖式分配客户端作用域。
     *
     * @param clientId 客户端主键
     * @param scopeIds 作用域主键集合,空集合表示清空
     * @throws club.beenest.iam.common.exception.BusinessException 客户端或作用域不存在、跨域
     */
    @PutMapping("/clients/{clientId}")
    @PreAuthorize("hasAuthority('client-scope:update')")
    @LogAudit(action = "CLIENT_SCOPE_ASSIGN", description = "分配客户端作用域")
    public Result<Void> assignClientScopes(@PathVariable Long clientId,
                                           @RequestBody List<Long> scopeIds) {
        clientScopeService.assignClientScopes(clientId, scopeIds);
        return Result.success();
    }
}
