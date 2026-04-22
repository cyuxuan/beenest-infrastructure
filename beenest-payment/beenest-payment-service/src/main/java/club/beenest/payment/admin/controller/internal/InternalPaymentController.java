package club.beenest.payment.admin.controller.internal;

import club.beenest.payment.common.Response;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.wallet.dto.WalletBalanceDTO;
import club.beenest.payment.wallet.dto.WalletAdminQueryDTO;
import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.wallet.domain.entity.WalletTransaction;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.withdraw.domain.entity.WithdrawRequest;
import club.beenest.payment.reconciliation.domain.entity.ReconciliationTask;
import club.beenest.payment.paymentorder.service.IPaymentService;
import club.beenest.payment.wallet.service.IWalletService;
import club.beenest.payment.withdraw.service.IWithdrawService;
import club.beenest.payment.reconciliation.service.IReconciliationService;
import com.github.pagehelper.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付中台内部 API Controller
 * 对应 PaymentFeignClient 定义的所有方法
 * 路径前缀 /internal/payment，仅接受内网调用
 *
 * <p>安全机制：通过 {@link club.beenest.payment.security.InternalApiFilter} 进行
 * IP 白名单 + Token + HMAC 签名三层校验。</p>
 *
 * @author System
 * @since 2026-02-11
 */
@RestController
@RequestMapping("/internal/payment")
@RequiredArgsConstructor
@Validated
public class InternalPaymentController {

    private final IWalletService walletService;
    private final IPaymentService paymentService;
    private final IWithdrawService withdrawService;
    private final IReconciliationService reconciliationService;

    // ==================== 钱包操作 ====================

    @GetMapping("/wallet/balance/{customerNo}")
    public Response<BigDecimal> getBalance(@PathVariable String customerNo,
                                           @RequestParam(value = "bizType", required = false) String bizType) {
        return Response.success(walletService.getBalance(customerNo, bizType));
    }

    @GetMapping("/wallet/detail/{customerNo}")
    public Response<WalletBalanceDTO> getWalletBalance(@PathVariable String customerNo,
                                                       @RequestParam(value = "bizType", required = false) String bizType) {
        return Response.success(walletService.getWalletBalance(customerNo, bizType));
    }

    @GetMapping("/wallet/{customerNo}")
    public Response<Wallet> getWallet(@PathVariable String customerNo,
                                      @RequestParam(value = "bizType", required = false) String bizType) {
        return Response.success(walletService.getWallet(customerNo, bizType));
    }

    @PostMapping("/wallet/create/{customerNo}")
    public Response<Wallet> createWallet(@PathVariable String customerNo,
                                         @RequestParam(value = "bizType", required = false) String bizType) {
        return Response.success(walletService.createWallet(customerNo, bizType));
    }

    @GetMapping("/wallet/transactions/{customerNo}")
    public Response<Map<String, Object>> getTransactionHistory(
            @PathVariable String customerNo,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam Integer pageNum,
            @RequestParam Integer pageSize,
            @RequestParam(required = false) String transactionType) {
        Page<TransactionHistoryDTO> page = walletService.getTransactionHistory(customerNo, bizType, pageNum, pageSize, transactionType);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @PostMapping("/wallet/add-balance")
    public Response<Void> addBalance(@RequestParam String customerNo,
                                     @RequestParam(value = "bizType", required = false) String bizType,
                                     @RequestParam BigDecimal amount,
                                     @RequestParam String description,
                                     @RequestParam String transactionType,
                                     @RequestParam(required = false) String referenceNo) {
        walletService.addBalance(customerNo, bizType, amount, description, transactionType, referenceNo);
        return Response.success();
    }

    @PostMapping("/wallet/deduct-balance")
    public Response<Boolean> deductBalance(@RequestParam String customerNo,
                                           @RequestParam(value = "bizType", required = false) String bizType,
                                           @RequestParam BigDecimal amount,
                                           @RequestParam String description,
                                           @RequestParam String transactionType,
                                           @RequestParam(required = false) String referenceNo) {
        return Response.success(walletService.deductBalance(customerNo, bizType, amount, description, transactionType, referenceNo));
    }

    @PostMapping("/wallet/freeze-balance")
    public Response<Boolean> freezeBalance(@RequestParam String customerNo,
                                           @RequestParam(value = "bizType", required = false) String bizType,
                                           @RequestParam Long amount,
                                           @RequestParam String description,
                                           @RequestParam(required = false) String referenceNo) {
        return Response.success(walletService.freezeBalance(customerNo, bizType, amount, description, referenceNo));
    }

    @PostMapping("/wallet/unfreeze-balance")
    public Response<Boolean> unfreezeBalance(@RequestParam String customerNo,
                                             @RequestParam(value = "bizType", required = false) String bizType,
                                             @RequestParam Long amount,
                                             @RequestParam String description,
                                             @RequestParam(required = false) String referenceNo) {
        return Response.success(walletService.unfreezeBalance(customerNo, bizType, amount, description, referenceNo));
    }

    // ==================== 充值 / 支付 ====================

    @PostMapping("/payment/recharge")
    public Response<Map<String, Object>> createRechargeOrder(@RequestParam String customerNo,
                                                              @Valid @RequestBody RechargeRequestDTO request) {
        return Response.success(paymentService.createRechargeOrder(customerNo, request));
    }

    @PostMapping("/payment/order-payment")
    public Response<Map<String, Object>> createOrderPayment(@RequestParam String customerNo,
                                                             @Valid @RequestBody OrderPaymentRequestDTO request) {
        return Response.success(paymentService.createOrderPayment(customerNo, request));
    }

    @GetMapping("/payment/status/{orderNo}")
    public Response<Map<String, Object>> queryPaymentStatus(@RequestParam String customerNo,
                                                             @PathVariable String orderNo) {
        return Response.success(paymentService.queryPaymentStatus(customerNo, orderNo));
    }

    @GetMapping("/payment/status-admin/{orderNo}")
    public Response<Map<String, Object>> queryPaymentStatusForAdmin(@PathVariable String orderNo) {
        return Response.success(paymentService.queryPaymentStatusForAdmin(orderNo));
    }

    @PostMapping("/payment/cancel/{orderNo}")
    public Response<Boolean> cancelRechargeOrder(@RequestParam String customerNo,
                                                  @PathVariable String orderNo) {
        return Response.success(paymentService.cancelRechargeOrder(customerNo, orderNo));
    }

    @PostMapping("/payment/orders")
    public Response<Map<String, Object>> queryOrders(@Valid @RequestBody PaymentOrderQueryDTO query,
                                                      @RequestParam int pageNum,
                                                      @RequestParam int pageSize) {
        Page<PaymentOrder> page = paymentService.queryOrders(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    // ==================== 退款 ====================

    @PostMapping("/refund/apply")
    public Response<Refund> applyRefund(@RequestParam String orderNo,
                                        @RequestParam Long amount,
                                        @RequestParam String reason) {
        return Response.success(paymentService.applyRefund(orderNo, amount, reason));
    }

    @PostMapping("/refund/request-review")
    public Response<Refund> requestRefundReview(@RequestParam String orderNo,
                                                 @RequestParam Long amount,
                                                 @RequestParam String reason) {
        return Response.success(paymentService.requestRefundReview(orderNo, amount, reason));
    }

    @PostMapping("/refund/list")
    public Response<Map<String, Object>> queryRefunds(@Valid @RequestBody RefundQueryDTO query,
                                                       @RequestParam int pageNum,
                                                       @RequestParam int pageSize) {
        Page<Refund> page = paymentService.queryRefunds(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @GetMapping("/refund/sync/{refundNo}")
    public Response<Map<String, Object>> syncRefundStatus(@PathVariable String refundNo) {
        return Response.success(paymentService.syncRefundStatus(refundNo));
    }

    @PostMapping("/refund/sync-processing")
    public Response<Map<String, Object>> syncProcessingRefunds(@RequestParam int limit) {
        return Response.success(paymentService.syncProcessingRefunds(limit));
    }

    @PostMapping("/refund/audit")
    public Response<Void> auditRefund(@RequestParam Long id,
                                       @RequestParam String status,
                                       @RequestParam String remark) {
        paymentService.auditRefund(id, status, remark);
        return Response.success();
    }

    // ==================== 提现 ====================

    @PostMapping("/withdraw/create")
    public Response<Map<String, Object>> createWithdrawRequest(@RequestParam String customerNo,
                                                                 @Valid @RequestBody WithdrawRequestDTO request) {
        return Response.success(withdrawService.createWithdrawRequest(customerNo, request));
    }

    @GetMapping("/withdraw/status/{requestNo}")
    public Response<Map<String, Object>> getWithdrawRequestStatus(@RequestParam String customerNo,
                                                                    @PathVariable String requestNo) {
        return Response.success(withdrawService.getWithdrawRequestStatus(customerNo, requestNo));
    }

    @PostMapping("/withdraw/audit")
    public Response<Void> auditWithdrawRequest(@RequestParam String requestNo,
                                                @RequestParam boolean approved,
                                                @RequestParam String auditUser,
                                                @RequestParam(required = false) String auditRemark) {
        withdrawService.auditWithdrawRequest(requestNo, approved, auditUser, auditRemark);
        return Response.success();
    }

    @PostMapping("/withdraw/process/{requestNo}")
    public Response<Boolean> processWithdrawRequest(@PathVariable String requestNo) {
        return Response.success(withdrawService.processWithdrawRequest(requestNo));
    }

    @PostMapping("/withdraw/cancel")
    public Response<Boolean> cancelWithdrawRequest(@RequestParam String customerNo,
                                                    @RequestParam String requestNo,
                                                    @RequestParam(required = false) String cancelReason) {
        return Response.success(withdrawService.cancelWithdrawRequest(customerNo, requestNo, cancelReason));
    }

    @PostMapping("/withdraw/list")
    public Response<Map<String, Object>> queryWithdrawRequests(@Valid @RequestBody WithdrawRequestQueryDTO query,
                                                                 @RequestParam int pageNum,
                                                                 @RequestParam int pageSize) {
        Page<WithdrawRequest> page = withdrawService.queryRequests(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    // ==================== 内部查询（供 drone-system 直接查询支付数据） ====================

    @GetMapping("/payment/latest-by-biz-no/{bizNo}")
    public Response<PaymentOrder> getLatestPaymentOrderByBizNo(@PathVariable String bizNo) {
        return Response.success(paymentService.getLatestPaymentOrderByBizNo(bizNo));
    }

    @GetMapping("/refund/list-by-order/{orderNo}")
    public Response<List<Refund>> getRefundsByOrderNo(@PathVariable String orderNo) {
        return Response.success(paymentService.getRefundsByOrderNo(orderNo));
    }

    @GetMapping("/refund/latest-pending-by-order/{orderNo}")
    public Response<Refund> getLatestPendingRefundByOrderNo(@PathVariable String orderNo) {
        return Response.success(paymentService.getLatestPendingRefundByOrderNo(orderNo));
    }

    @GetMapping("/wallet/transactions/raw/{customerNo}")
    public Response<Map<String, Object>> getTransactionsByCustomerNo(
            @PathVariable String customerNo,
            @RequestParam(required = false) String transactionType,
            @RequestParam Integer pageNum,
            @RequestParam Integer pageSize) {
        Page<WalletTransaction> page = walletService.getTransactionsByCustomerNo(customerNo, transactionType, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @GetMapping("/wallet/transactions/statistics/{customerNo}")
    public Response<List<WalletTransaction>> getIncomeStatistics(
            @PathVariable String customerNo,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return Response.success(walletService.getIncomeStatistics(customerNo, startTime, endTime));
    }

    // ==================== 管理端 ====================

    @PostMapping("/admin/wallets")
    public Response<Map<String, Object>> queryWallets(@Valid @RequestBody WalletAdminQueryDTO query,
                                                       @RequestParam Integer pageNum,
                                                       @RequestParam Integer pageSize) {
        Page<Wallet> page = walletService.queryWallets(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @PostMapping("/admin/transactions")
    public Response<Map<String, Object>> queryTransactions(@Valid @RequestBody TransactionQueryDTO query,
                                                             @RequestParam Integer pageNum,
                                                             @RequestParam Integer pageSize) {
        Page<TransactionHistoryDTO> page = walletService.queryTransactions(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @PostMapping("/admin/reconciliation/tasks")
    public Response<Map<String, Object>> queryReconciliationTasks(@Valid @RequestBody ReconciliationQueryDTO query,
                                                                    @RequestParam int pageNum,
                                                                    @RequestParam int pageSize) {
        Page<ReconciliationTask> page = reconciliationService.queryTasks(query, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", page.getTotal());
        result.put("list", page.getResult());
        result.put("pageNum", page.getPageNum());
        result.put("pageSize", page.getPageSize());
        return Response.success(result);
    }

    @PostMapping("/admin/reconciliation/create")
    public Response<Void> createReconciliationTask(@RequestParam String date,
                                                     @RequestParam String channel) {
        reconciliationService.createTask(date, channel);
        return Response.success();
    }
}
