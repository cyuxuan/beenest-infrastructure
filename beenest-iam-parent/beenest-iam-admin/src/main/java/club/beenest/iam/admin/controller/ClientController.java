package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.admin.dto.ClientResponse;
import club.beenest.iam.admin.dto.ClientTypeChangeRequest;
import club.beenest.iam.admin.dto.ClientUpdateRequest;
import club.beenest.iam.admin.service.ClientService;
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

/**
 * 客户端管理接口。
 * <p>机密客户端的密钥明文只在创建与轮换响应中返回一次。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/clients")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * 分页查询客户端。
     *
     * @param clientKey  客户端标识(模糊匹配,可空)
     * @param clientName 客户端名称(模糊匹配,可空)
     * @param clientType 客户端类型(精确匹配,可空)
     * @param enabled    启用状态(精确匹配,可空)
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @return 客户端分页结果,不回显密钥
     */
    @GetMapping
    @PreAuthorize("hasAuthority('client:view')")
    public Result<PageResult<ClientResponse>> page(
            @RequestParam(required = false) String clientKey,
            @RequestParam(required = false) String clientName,
            @RequestParam(required = false) String clientType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(clientService.page(clientKey, clientName, clientType, enabled, pageNum, pageSize));
    }

    /**
     * 查询单个客户端。
     *
     * @param id 客户端主键
     * @return 客户端详情,不含密钥明文
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('client:view')")
    public Result<ClientResponse> get(@PathVariable Long id) {
        return Result.success(clientService.get(id));
    }

    /**
     * 新建客户端。
     *
     * @param request 新建请求,包含客户端标识、类型与回调配置;客户端标识域内唯一
     * @return 新建的客户端;机密客户端响应携带一次性明文密钥,公共客户端为空
     * @throws club.beenest.iam.common.exception.BusinessException 客户端标识重复或配置校验不通过
     */
    @PostMapping
    @PreAuthorize("hasAuthority('client:create')")
    @LogAudit(action = "CLIENT_CREATE", description = "新建客户端")
    public Result<ClientResponse> create(@Valid @RequestBody ClientCreateRequest request) {
        return Result.success(clientService.create(request));
    }

    /**
     * 更新客户端。
     *
     * @param id      客户端主键
     * @param request 更新请求,仅覆盖显式给出的字段
     * @return 更新后的客户端
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在或更新后的配置校验不通过
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('client:update')")
    @LogAudit(action = "CLIENT_UPDATE", description = "更新客户端")
    public Result<ClientResponse> update(@PathVariable Long id,
                                         @Valid @RequestBody ClientUpdateRequest request) {
        return Result.success(clientService.update(id, request));
    }

    /**
     * 删除客户端(逻辑删除)。
     *
     * @param id 客户端主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('client:delete')")
    @LogAudit(action = "CLIENT_DELETE", description = "删除客户端")
    public Result<Void> delete(@PathVariable Long id) {
        clientService.delete(id);
        return Result.success();
    }

    /**
     * 变更客户端类型(机密 / 公开)。
     * <p>类型决定令牌端点的认证方式,单独成动作:只有尚未投入使用的客户端允许变更,
     * 升级为机密客户端时响应携带一次性明文密钥。</p>
     *
     * @param id      客户端主键
     * @param request 目标类型
     * @return 变更后的客户端;升级为机密客户端时携带新的明文密钥,仅本次返回
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在、不属于当前域、已投入使用,或与凭据模式冲突
     */
    @PostMapping("/{id}/type")
    @PreAuthorize("hasAuthority('client:update')")
    @LogAudit(action = "CLIENT_CHANGE_TYPE", description = "变更客户端类型")
    public Result<ClientResponse> changeType(@PathVariable Long id,
                                             @Valid @RequestBody ClientTypeChangeRequest request) {
        return Result.success(clientService.changeType(id, request));
    }

    /**
     * 轮换机密客户端密钥。
     *
     * @param id 客户端主键
     * @return 客户端详情,响应携带新的明文密钥,仅本次返回
     * @throws club.beenest.iam.common.exception.BusinessException 客户端不存在、不属于当前域或为公共客户端
     */
    @PostMapping("/{id}/secret")
    @PreAuthorize("hasAuthority('client:update')")
    @LogAudit(action = "CLIENT_ROTATE_SECRET", description = "轮换客户端密钥")
    public Result<ClientResponse> rotateSecret(@PathVariable Long id) {
        return Result.success(clientService.rotateSecret(id));
    }
}
