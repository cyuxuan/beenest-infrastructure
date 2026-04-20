package club.beenest.payment.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.object.entity.RiskRule;
import club.beenest.payment.service.IRiskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 支付风控控制器
 * 提供风控规则的管理接口，包括查询、创建、更新、删除风控规则
 *
 * @author System
 * @since 2026-02-11
 */
@Tag(name = "支付风控管理", description = "支付风控规则API")
@RestController
@RequestMapping("/api/admin/payment/risk")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class PaymentRiskController {

    @Autowired
    private IRiskRuleService riskRuleService;

    @Operation(summary = "查询风控规则", description = "查询所有风控规则")
    @GetMapping("/rules")
    @LogAudit(module = "支付风控", operation = "查询风控规则")
    public Response<List<RiskRule>> getRiskRules() {
        return Response.success(riskRuleService.getRules());
    }

    /**
     * 创建风控规则
     *
     * @param rule 风控规则参数
     * @return 操作结果
     */
    @Operation(summary = "创建风控规则", description = "创建新的风控规则")
    @PostMapping("/rules/create")
    @LogAudit(module = "支付风控", operation = "创建风控规则")
    public Response<Void> createRiskRule(@Valid @RequestBody RiskRule rule) {
        riskRuleService.createRule(rule);
        return Response.success();
    }

    /**
     * 更新风控规则
     *
     * @param rule 风控规则参数（必须包含id）
     * @return 操作结果
     */
    @Operation(summary = "更新风控规则", description = "更新风控规则")
    @PostMapping("/rules/update")
    @LogAudit(module = "支付风控", operation = "更新风控规则")
    public Response<Void> updateRiskRule(@Valid @RequestBody RiskRule rule) {
        riskRuleService.updateRule(rule);
        return Response.success();
    }

    @Operation(summary = "删除风控规则", description = "删除风控规则")
    @PostMapping("/rules/delete/{id}")
    @LogAudit(module = "支付风控", operation = "删除风控规则")
    public Response<Void> deleteRiskRule(@PathVariable Long id) {
        riskRuleService.deleteRule(id);
        return Response.success();
    }
}
