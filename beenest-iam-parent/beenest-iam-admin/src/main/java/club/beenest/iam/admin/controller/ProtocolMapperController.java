package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.ProtocolMapperCreateRequest;
import club.beenest.iam.admin.dto.ProtocolMapperResponse;
import club.beenest.iam.admin.dto.ProtocolMapperUpdateRequest;
import club.beenest.iam.admin.service.ProtocolMapperService;
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
 * 协议映射器管理接口。
 * <p>把用户属性/固定值映射为令牌 claim;域级映射对全部客户端生效,客户端级映射按客户端定制。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/protocol-mappers")
public class ProtocolMapperController {

    private final ProtocolMapperService protocolMapperService;

    public ProtocolMapperController(ProtocolMapperService protocolMapperService) {
        this.protocolMapperService = protocolMapperService;
    }

    /**
     * 分页查询映射器(不传 clientId 时只查域级)。
     *
     * @param clientId 客户端主键,传入时查该客户端生效的映射(可空)
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 当前域映射器分页结果
     * @throws club.beenest.iam.common.exception.BusinessException 指定客户端不存在或不属于当前域
     */
    @GetMapping
    @PreAuthorize("hasAuthority('protocol-mapper:view')")
    public Result<PageResult<ProtocolMapperResponse>> page(
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(protocolMapperService.page(clientId, pageNum, pageSize));
    }

    /**
     * 查询单个映射器。
     *
     * @param id 映射器主键
     * @return 映射器详情
     * @throws club.beenest.iam.common.exception.BusinessException 映射器不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('protocol-mapper:view')")
    public Result<ProtocolMapperResponse> get(@PathVariable Long id) {
        return Result.success(protocolMapperService.get(id));
    }

    /**
     * 新建映射器。
     *
     * @param request 新建请求;userAttribute 与 claimTemplate 必须且只能提供一个,claim 类型限 STRING/NUMBER/BOOLEAN/JSON,客户端级映射必须挂靠本域客户端
     * @return 新建的映射器
     * @throws club.beenest.iam.common.exception.BusinessException 映射来源配置冲突、claim 类型非法或客户端跨域
     */
    @PostMapping
    @PreAuthorize("hasAuthority('protocol-mapper:create')")
    @LogAudit(action = "PROTOCOL_MAPPER_CREATE", description = "新建协议映射器")
    public Result<ProtocolMapperResponse> create(@Valid @RequestBody ProtocolMapperCreateRequest request) {
        return Result.success(protocolMapperService.create(request));
    }

    /**
     * 更新映射器。
     *
     * @param id      映射器主键
     * @param request 更新请求;给出 userAttribute 或 claimTemplate 时互斥覆盖另一侧
     * @return 更新后的映射器
     * @throws club.beenest.iam.common.exception.BusinessException 映射器不存在或 claim 类型非法
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('protocol-mapper:update')")
    @LogAudit(action = "PROTOCOL_MAPPER_UPDATE", description = "更新协议映射器")
    public Result<ProtocolMapperResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody ProtocolMapperUpdateRequest request) {
        return Result.success(protocolMapperService.update(id, request));
    }

    /**
     * 删除映射器。
     *
     * @param id 映射器主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 映射器不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('protocol-mapper:delete')")
    @LogAudit(action = "PROTOCOL_MAPPER_DELETE", description = "删除协议映射器")
    public Result<Void> delete(@PathVariable Long id) {
        protocolMapperService.delete(id);
        return Result.success();
    }
}
