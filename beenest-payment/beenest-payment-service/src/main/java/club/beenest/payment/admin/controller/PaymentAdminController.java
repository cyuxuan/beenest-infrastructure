package club.beenest.payment.admin.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.CsvExportUtils;
import club.beenest.payment.paymentorder.dto.BatchSyncResultDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RefundApplyDTO;
import club.beenest.payment.paymentorder.dto.RefundAuditDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundSyncResultDTO;
import club.beenest.payment.shared.domain.entity.PaymentChannelConfig;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import club.beenest.payment.shared.service.IPaymentConfigService;
import club.beenest.payment.paymentorder.service.IPaymentService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 支付管理后台控制器（订单+退款+配置）
 */
@Tag(name = "支付管理后台", description = "支付管理后台API接口")
@RestController
@RequestMapping("/api/admin/payment")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Slf4j
public class PaymentAdminController {

    @Autowired
    private IPaymentService paymentService;

    @Autowired
    private IPaymentConfigService paymentConfigService;

    @Operation(summary = "查询充值订单", description = "分页查询充值订单列表")
    @PostMapping("/orders/page")
    @LogAudit(module = "支付管理", operation = "查询充值订单")
    public Response<PageInfo<PaymentOrder>> queryOrders(@Valid @RequestBody PaymentOrderQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<PaymentOrder> page = paymentService.queryOrders(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "同步订单状态", description = "主动同步第三方支付订单状态")
    @PostMapping("/orders/{orderNo}/sync")
    @LogAudit(module = "支付管理", operation = "同步订单状态")
    public Response<PaymentStatusDTO> syncOrder(@PathVariable String orderNo) {
        return Response.success(paymentService.queryPaymentStatusForAdmin(orderNo));
    }

    @Operation(summary = "导出订单", description = "导出订单数据")
    @PostMapping("/orders/export")
    @LogAudit(module = "支付管理", operation = "导出订单")
    public void exportOrders(@Valid @RequestBody PaymentOrderQueryDTO query, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=orders.csv");
        response.getOutputStream().write(CsvExportUtils.bom());
        StringBuilder csv = new StringBuilder();
        csv.append("OrderNo,CustomerNo,Amount,Platform,Status,CreateTime\n");
        Page<PaymentOrder> page = paymentService.queryOrders(query, 1, 10000);
        for (PaymentOrder order : page) {
            csv.append(CsvExportUtils.escapeValue(order.getOrderNo())).append(",")
                    .append(CsvExportUtils.escapeValue(order.getCustomerNo())).append(",")
                    .append(order.getAmount()).append(",")
                    .append(CsvExportUtils.escapeValue(order.getPlatform())).append(",")
                    .append(CsvExportUtils.escapeValue(order.getStatus())).append(",")
                    .append(CsvExportUtils.escapeValue(order.getCreateTime() != null ? order.getCreateTime().toString() : ""))
                    .append("\n");
        }
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "申请退款", description = "申请订单退款")
    @PostMapping("/refunds/apply")
    @LogAudit(module = "支付管理", operation = "申请退款")
    public Response<Refund> applyRefund(@RequestBody @Valid RefundApplyDTO params) {
        return Response.success(paymentService.applyRefund(params.getOrderNo(), params.getAmount(), params.getReason()));
    }

    @Operation(summary = "查询退款记录", description = "分页查询退款记录")
    @PostMapping("/refunds/page")
    @LogAudit(module = "支付管理", operation = "查询退款记录")
    public Response<PageInfo<Refund>> queryRefunds(@Valid @RequestBody RefundQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<Refund> page = paymentService.queryRefunds(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "审核退款", description = "审核退款申请")
    @PostMapping("/refunds/audit")
    @LogAudit(module = "支付管理", operation = "审核退款")
    public Response<Void> auditRefund(@RequestBody @Valid RefundAuditDTO params) {
        paymentService.auditRefund(params.getId(), params.getStatus(), params.getRemark());
        return Response.success();
    }

    @Operation(summary = "同步退款状态", description = "主动同步第三方退款状态")
    @PostMapping("/refunds/{refundNo}/sync")
    @LogAudit(module = "支付管理", operation = "同步退款状态")
    public Response<RefundSyncResultDTO> syncRefund(@PathVariable String refundNo) {
        return Response.success(paymentService.syncRefundStatus(refundNo));
    }

    @Operation(summary = "批量同步处理中退款", description = "批量同步第三方处理中退款状态")
    @PostMapping("/refunds/sync-processing")
    @LogAudit(module = "支付管理", operation = "批量同步处理中退款")
    public Response<BatchSyncResultDTO> syncProcessingRefunds(
            @RequestParam(defaultValue = "20") int limit) {
        return Response.success(paymentService.syncProcessingRefunds(limit));
    }

    @Operation(summary = "导出退款", description = "导出退款数据")
    @PostMapping("/refunds/export")
    @LogAudit(module = "支付管理", operation = "导出退款")
    public void exportRefunds(@Valid @RequestBody RefundQueryDTO query, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=refunds.csv");
        response.getOutputStream().write(CsvExportUtils.bom());
        StringBuilder csv = new StringBuilder();
        csv.append("RefundNo,OrderNo,Amount,Reason,Status,RefundPolicy,RequestSource,ApplicantId,ChannelStatus,CreateTime\n");
        Page<Refund> page = paymentService.queryRefunds(query, 1, 10000);
        for (Refund refund : page) {
            csv.append(CsvExportUtils.escapeValue(refund.getRefundNo())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getOrderNo())).append(",")
                    .append(refund.getAmount()).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getReason())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getStatus())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getRefundPolicy())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getRequestSource())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getApplicantId())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getChannelStatus())).append(",")
                    .append(CsvExportUtils.escapeValue(refund.getCreateTime() != null ? refund.getCreateTime().toString() : ""))
                    .append("\n");
        }
        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "查询支付配置", description = "获取所有支付渠道配置")
    @GetMapping("/configs/list")
    public Response<List<PaymentChannelConfig>> getPaymentConfigs() {
        return Response.success(paymentConfigService.getAllConfigs());
    }

    @Operation(summary = "更新支付配置", description = "更新支付渠道配置")
    @PostMapping("/configs/update")
    @LogAudit(module = "支付管理", operation = "更新支付配置")
    public Response<Void> updatePaymentConfig(@Valid @RequestBody PaymentChannelConfig config) {
        paymentConfigService.updateConfig(config);
        return Response.success();
    }
}
