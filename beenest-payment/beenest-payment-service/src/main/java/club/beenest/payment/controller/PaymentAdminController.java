package club.beenest.payment.controller;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.object.dto.PaymentOrderQueryDTO;
import club.beenest.payment.object.dto.RefundApplyDTO;
import club.beenest.payment.object.dto.RefundAuditDTO;
import club.beenest.payment.object.dto.RefundQueryDTO;
import club.beenest.payment.object.dto.TransactionHistoryDTO;
import club.beenest.payment.object.dto.TransactionQueryDTO;
import club.beenest.payment.object.dto.WalletAdminQueryDTO;
import club.beenest.payment.object.dto.WithdrawAuditDTO;
import club.beenest.payment.object.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.object.entity.PaymentChannelConfig;
import club.beenest.payment.object.entity.PaymentOrder;
import club.beenest.payment.object.entity.Refund;
import club.beenest.payment.object.entity.Wallet;
import club.beenest.payment.object.entity.WithdrawRequest;
import club.beenest.payment.service.IPaymentConfigService;
import club.beenest.payment.service.IPaymentService;
import club.beenest.payment.service.IWalletService;
import club.beenest.payment.service.IWithdrawService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageInfo;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Map;

/**
 * 支付管理后台控制器
 * 提供支付、提现相关的管理后台API接口
 *
 * @author System
 * @since 2026-02-11
 */
@Tag(name = "支付管理后台", description = "支付管理后台API接口")
@RestController
@RequestMapping("/api/admin/payment")
@SaCheckRole("admin")
@Validated
@Slf4j
public class PaymentAdminController {

    @Autowired
    private IPaymentService paymentService;

    @Autowired
    private IWithdrawService withdrawService;

    @Autowired
    private IWalletService walletService;

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

    @Operation(summary = "查询提现申请", description = "分页查询提现申请列表")
    @PostMapping("/withdraws/page")
    @LogAudit(module = "支付管理", operation = "查询提现申请")
    public Response<PageInfo<WithdrawRequest>> queryWithdrawRequests(@Valid @RequestBody WithdrawRequestQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<WithdrawRequest> page = withdrawService.queryRequests(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "审核提现申请", description = "审核提现申请（通过/拒绝）")
    @PostMapping("/withdraws/audit")
    @LogAudit(module = "支付管理", operation = "审核提现申请")
    public Response<Void> auditWithdrawRequest(@RequestBody @Valid WithdrawAuditDTO audit) {
        String currentUserId = AuthUtils.getCurrentUserId();
        if (currentUserId == null) {
            return Response.fail(401, "未获取到当前登录用户信息");
        }
        audit.setAuditBy(currentUserId);

        withdrawService.auditRequest(audit);
        return Response.success();
    }

    @Operation(summary = "查询钱包", description = "分页查询钱包列表")
    @PostMapping("/wallets/page")
    @LogAudit(module = "支付管理", operation = "查询钱包")
    public Response<PageInfo<Wallet>> queryWallets(@Valid @RequestBody WalletAdminQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<Wallet> page = walletService.queryWallets(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    @Operation(summary = "同步订单状态", description = "主动同步第三方支付订单状态")
    @PostMapping("/orders/{orderNo}/sync")
    @LogAudit(module = "支付管理", operation = "同步订单状态")
    public Response<Map<String, Object>> syncOrder(@PathVariable String orderNo) {
        return Response.success(paymentService.queryPaymentStatusForAdmin(orderNo));
    }

    @Operation(summary = "申请退款", description = "申请订单退款")
    @PostMapping("/refunds/apply")
    @LogAudit(module = "支付管理", operation = "申请退款")
    public Response<Refund> applyRefund(@RequestBody @Valid RefundApplyDTO params) {
        return Response.success(paymentService.applyRefund(params.getOrderNo(), params.getAmount(), params.getReason()));
    }

    @Operation(summary = "同步退款状态", description = "主动同步第三方退款状态")
    @PostMapping("/refunds/{refundNo}/sync")
    @LogAudit(module = "支付管理", operation = "同步退款状态")
    public Response<Map<String, Object>> syncRefund(@PathVariable String refundNo) {
        return Response.success(paymentService.syncRefundStatus(refundNo));
    }

    @Operation(summary = "批量同步处理中退款", description = "批量同步第三方处理中退款状态")
    @PostMapping("/refunds/sync-processing")
    @LogAudit(module = "支付管理", operation = "批量同步处理中退款")
    public Response<Map<String, Object>> syncProcessingRefunds(
            @RequestParam(defaultValue = "20") int limit) {
        return Response.success(paymentService.syncProcessingRefunds(limit));
    }

    @Operation(summary = "导出订单", description = "导出订单数据")
    @PostMapping("/orders/export")
    @LogAudit(module = "支付管理", operation = "导出订单")
    public void exportOrders(@Valid @RequestBody PaymentOrderQueryDTO query, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=orders.csv");

        // Write BOM for Excel compatibility
        response.getOutputStream().write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        StringBuilder csv = new StringBuilder();
        csv.append("OrderNo,CustomerNo,Amount,Platform,Status,CreateTime\n");

        Page<PaymentOrder> page = paymentService.queryOrders(query, 1, 10000); // Export up to 10000 records
        for (PaymentOrder order : page) {
            csv.append(escapeCsvValue(order.getOrderNo())).append(",")
                    .append(escapeCsvValue(order.getCustomerNo())).append(",")
                    .append(order.getAmount()).append(",")
                    .append(escapeCsvValue(order.getPlatform())).append(",")
                    .append(escapeCsvValue(order.getStatus())).append(",")
                    .append(escapeCsvValue(order.getCreateTime() != null ? order.getCreateTime().toString() : ""))
                    .append("\n");
        }

        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "导出退款", description = "导出退款数据")
    @PostMapping("/refunds/export")
    @LogAudit(module = "支付管理", operation = "导出退款")
    public void exportRefunds(@Valid @RequestBody RefundQueryDTO query, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=refunds.csv");
        response.getOutputStream().write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        StringBuilder csv = new StringBuilder();
        csv.append("RefundNo,OrderNo,Amount,Reason,Status,RefundPolicy,RequestSource,ApplicantId,ChannelStatus,CreateTime\n");

        Page<Refund> page = paymentService.queryRefunds(query, 1, 10000);
        for (Refund refund : page) {
            csv.append(escapeCsvValue(refund.getRefundNo())).append(",")
                    .append(escapeCsvValue(refund.getOrderNo())).append(",")
                    .append(refund.getAmount()).append(",")
                    .append(escapeCsvValue(refund.getReason())).append(",")
                    .append(escapeCsvValue(refund.getStatus())).append(",")
                    .append(escapeCsvValue(refund.getRefundPolicy())).append(",")
                    .append(escapeCsvValue(refund.getRequestSource())).append(",")
                    .append(escapeCsvValue(refund.getApplicantId())).append(",")
                    .append(escapeCsvValue(refund.getChannelStatus())).append(",")
                    .append(escapeCsvValue(refund.getCreateTime() != null ? refund.getCreateTime().toString() : ""))
                    .append("\n");
        }

        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "导出提现", description = "导出提现数据")
    @PostMapping("/withdraws/export")
    @LogAudit(module = "支付管理", operation = "导出提现")
    public void exportWithdraws(@Valid @RequestBody WithdrawRequestQueryDTO query, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=withdraws.csv");

        response.getOutputStream().write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        StringBuilder csv = new StringBuilder();
        csv.append("RequestNo,CustomerNo,Amount,Status,CreateTime\n");

        Page<WithdrawRequest> page = withdrawService.queryRequests(query, 1, 10000);
        for (WithdrawRequest req : page) {
            csv.append(escapeCsvValue(req.getRequestNo())).append(",")
                    .append(escapeCsvValue(req.getCustomerNo())).append(",")
                    .append(req.getAmount()).append(",")
                    .append(escapeCsvValue(req.getStatus())).append(",")
                    .append(escapeCsvValue(req.getCreateTime() != null ? req.getCreateTime().toString() : ""))
                    .append("\n");
        }

        response.getOutputStream().write(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "导出交易流水", description = "导出交易流水数据")
    @PostMapping("/transactions/export")
    @LogAudit(module = "支付管理", operation = "导出交易流水")
    public void exportTransactions(@Valid @RequestBody TransactionQueryDTO query, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=transactions.csv");
        response.getOutputStream().write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });

        StringBuilder csv = new StringBuilder();
        csv.append("TransactionNo,Type,Amount,Status,CreateTime\n");

        Page<TransactionHistoryDTO> page = walletService.queryTransactions(query, 1, 10000);
        for (TransactionHistoryDTO t : page) {
            csv.append(escapeCsvValue(t.getTransactionNo())).append(",")
                    .append(escapeCsvValue(t.getTransactionTypeDisplayName())).append(",")
                    .append(t.getAmountInYuan()).append(",")
                    .append(escapeCsvValue(t.getStatusDisplayName())).append(",")
                    .append(escapeCsvValue(t.getFormattedCreateTime())).append("\n");
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

    @Operation(summary = "查询交易记录", description = "分页查询交易流水")
    @PostMapping("/transactions/page")
    @LogAudit(module = "支付管理", operation = "查询交易记录")
    public Response<PageInfo<TransactionHistoryDTO>> queryTransactions(@Valid @RequestBody TransactionQueryDTO query,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<TransactionHistoryDTO> page = walletService.queryTransactions(query, pageNum, pageSize);
        return Response.success(new PageInfo<>(page));
    }

    /**
     * CSV值转义方法，防止CSV注入攻击
     * 处理包含逗号、换行符、引号以及公式字符的字段
     */
    private String escapeCsvValue(String value) {
        if (value == null) {
            return "";
        }

        // 转义CSV公式字符（=, +, -, @）防止Excel公式注入
        if (value.startsWith("=") || value.startsWith("+") ||
                value.startsWith("-") || value.startsWith("@")) {
            value = "'" + value; // 在公式字符前添加单引号，使Excel将其视为文本
        }

        // 如果包含逗号、换行、引号，需要用引号包裹并转义
        if (value.contains(",") || value.contains("\n") ||
                value.contains("\r") || value.contains("\"")) {
            value = "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}
