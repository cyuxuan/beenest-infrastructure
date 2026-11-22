package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AttributeDefCreateRequest;
import club.beenest.iam.admin.dto.AttributeDefResponse;
import club.beenest.iam.admin.dto.AttributeDefUpdateRequest;
import club.beenest.iam.admin.service.AttributeDefService;
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
 * 用户属性定义管理接口。
 * <p>声明式 Schema:驱动后台属性构建器、用户资料校验与属性索引投影。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/attribute-defs")
public class AttributeDefController {

    private final AttributeDefService attributeDefService;

    public AttributeDefController(AttributeDefService attributeDefService) {
        this.attributeDefService = attributeDefService;
    }

    /**
     * 查询域内全部属性定义。
     *
     * @return 当前域的属性定义列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('attribute-def:view')")
    public Result<List<AttributeDefResponse>> list() {
        return Result.success(attributeDefService.list());
    }

    /**
     * 查询单个属性定义。
     *
     * @param id 属性定义主键
     * @return 属性定义详情
     * @throws club.beenest.iam.common.exception.BusinessException 属性定义不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('attribute-def:view')")
    public Result<AttributeDefResponse> get(@PathVariable Long id) {
        return Result.success(attributeDefService.get(id));
    }

    /**
     * 新建属性定义。
     *
     * @param request 新建请求,包含属性键、显示名、类型与可选值;属性键在域内唯一,类型限 STRING/NUMBER/BOOLEAN/DATE/ENUM/JSON,ENUM 必须提供可选值
     * @return 新建的属性定义
     * @throws club.beenest.iam.common.exception.BusinessException 属性键重复或类型、可选值不合法
     */
    @PostMapping
    @PreAuthorize("hasAuthority('attribute-def:create')")
    @LogAudit(action = "ATTRIBUTE_DEF_CREATE", description = "新建属性定义")
    public Result<AttributeDefResponse> create(@Valid @RequestBody AttributeDefCreateRequest request) {
        return Result.success(attributeDefService.create(request));
    }

    /**
     * 更新属性定义。
     *
     * @param id      属性定义主键
     * @param request 更新请求,仅覆盖显式给出的字段
     * @return 更新后的属性定义
     * @throws club.beenest.iam.common.exception.BusinessException 属性定义不存在或更新后的类型不合法
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('attribute-def:update')")
    @LogAudit(action = "ATTRIBUTE_DEF_UPDATE", description = "更新属性定义")
    public Result<AttributeDefResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody AttributeDefUpdateRequest request) {
        return Result.success(attributeDefService.update(id, request));
    }

    /**
     * 删除属性定义。
     *
     * @param id 属性定义主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 属性定义不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('attribute-def:delete')")
    @LogAudit(action = "ATTRIBUTE_DEF_DELETE", description = "删除属性定义")
    public Result<Void> delete(@PathVariable Long id) {
        attributeDefService.delete(id);
        return Result.success();
    }
}
