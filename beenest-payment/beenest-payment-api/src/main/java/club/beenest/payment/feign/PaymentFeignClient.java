package club.beenest.payment.feign;

import club.beenest.payment.common.Response;
import club.beenest.payment.object.dto.*;
import club.beenest.payment.object.entity.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 支付中台 Feign Client
 * 主业务服务通过此接口调用支付中台的内部 API
 *
 * <p>通过 Nacos 服务发现路由到 beenest-payment 服务，
 * Sentinel 提供熔断降级保护。</p>
 */
@FeignClient(
        name = "beenest-payment",
        contextId = "paymentFeignClient",
        path = "/internal/payment",
        fallbackFactory = PaymentFeignFallbackFactory.class
)
public interface PaymentFeignClient {

    // ==================== 钱包操作 ====================

    @GetMapping("/wallet/balance/{customerNo}")
    Response<BigDecimal> getBalance(@PathVariable("customerNo") String customerNo,
                                    @RequestParam(value = "bizType", required = false) String bizType);

    @GetMapping("/wallet/detail/{customerNo}")
    Response<WalletBalanceDTO> getWalletBalance(@PathVariable("customerNo") String customerNo,
                                                @RequestParam(value = "bizType", required = false) String bizType);

    @GetMapping("/wallet/{customerNo}")
    Response<Wallet> getWallet(@PathVariable("customerNo") String customerNo,
                               @RequestParam(value = "bizType", required = false) String bizType);

    @PostMapping("/wallet/create/{customerNo}")
    Response<Wallet> createWallet(@PathVariable("customerNo") String customerNo,
                                  @RequestParam(value = "bizType", required = false) String bizType);

    @GetMapping("/wallet/transactions/{customerNo}")
    Response<Map<String, Object>> getTransactionHistory(
            @PathVariable("customerNo") String customerNo,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam("pageNum") Integer pageNum,
            @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "transactionType", required = false) String transactionType);

    @PostMapping("/wallet/add-balance")
    Response<Void> addBalance(@RequestParam("customerNo") String customerNo,
                              @RequestParam(value = "bizType", required = false) String bizType,
                              @RequestParam("amount") BigDecimal amount,
                              @RequestParam("description") String description,
                              @RequestParam("transactionType") String transactionType,
                              @RequestParam(value = "referenceNo", required = false) String referenceNo);

    @PostMapping("/wallet/deduct-balance")
    Response<Boolean> deductBalance(@RequestParam("customerNo") String customerNo,
                                    @RequestParam(value = "bizType", required = false) String bizType,
                                    @RequestParam("amount") BigDecimal amount,
                                    @RequestParam("description") String description,
                                    @RequestParam("transactionType") String transactionType,
                                    @RequestParam(value = "referenceNo", required = false) String referenceNo);

    // ==================== 充值 / 支付 ====================

    @PostMapping("/payment/recharge")
    Response<Map<String, Object>> createRechargeOrder(@RequestParam("customerNo") String customerNo,
                                                       @RequestBody RechargeRequestDTO request);

    @PostMapping("/payment/order-payment")
    Response<Map<String, Object>> createOrderPayment(@RequestParam("customerNo") String customerNo,
                                                      @RequestBody OrderPaymentRequestDTO request);

    @GetMapping("/payment/status/{orderNo}")
    Response<Map<String, Object>> queryPaymentStatus(@RequestParam("customerNo") String customerNo,
                                                      @PathVariable("orderNo") String orderNo);

    @GetMapping("/payment/status-admin/{orderNo}")
    Response<Map<String, Object>> queryPaymentStatusForAdmin(@PathVariable("orderNo") String orderNo);

    @PostMapping("/payment/cancel/{orderNo}")
    Response<Boolean> cancelRechargeOrder(@RequestParam("customerNo") String customerNo,
                                           @PathVariable("orderNo") String orderNo);

    @PostMapping("/payment/orders")
    Response<Map<String, Object>> queryOrders(@RequestBody PaymentOrderQueryDTO query,
                                               @RequestParam("pageNum") int pageNum,
                                               @RequestParam("pageSize") int pageSize);

    // ==================== 退款 ====================

    @PostMapping("/refund/apply")
    Response<Refund> applyRefund(@RequestParam("orderNo") String orderNo,
                                 @RequestParam("amount") Long amount,
                                 @RequestParam("reason") String reason);

    @PostMapping("/refund/request-review")
    Response<Refund> requestRefundReview(@RequestParam("orderNo") String orderNo,
                                          @RequestParam("amount") Long amount,
                                          @RequestParam("reason") String reason);

    @PostMapping("/refund/list")
    Response<Map<String, Object>> queryRefunds(@RequestBody RefundQueryDTO query,
                                                @RequestParam("pageNum") int pageNum,
                                                @RequestParam("pageSize") int pageSize);

    @GetMapping("/refund/sync/{refundNo}")
    Response<Map<String, Object>> syncRefundStatus(@PathVariable("refundNo") String refundNo);

    @PostMapping("/refund/sync-processing")
    Response<Map<String, Object>> syncProcessingRefunds(@RequestParam("limit") int limit);

    @PostMapping("/refund/audit")
    Response<Void> auditRefund(@RequestParam("id") Long id,
                                @RequestParam("status") String status,
                                @RequestParam("remark") String remark);

    // ==================== 提现 ====================

    @PostMapping("/withdraw/create")
    Response<Map<String, Object>> createWithdrawRequest(@RequestParam("customerNo") String customerNo,
                                                          @RequestBody WithdrawRequestDTO request);

    @GetMapping("/withdraw/status/{requestNo}")
    Response<Map<String, Object>> getWithdrawRequestStatus(@RequestParam("customerNo") String customerNo,
                                                            @PathVariable("requestNo") String requestNo);

    @PostMapping("/withdraw/audit")
    Response<Void> auditWithdrawRequest(@RequestParam("requestNo") String requestNo,
                                         @RequestParam("approved") boolean approved,
                                         @RequestParam("auditUser") String auditUser,
                                         @RequestParam(value = "auditRemark", required = false) String auditRemark);

    @PostMapping("/withdraw/process/{requestNo}")
    Response<Boolean> processWithdrawRequest(@PathVariable("requestNo") String requestNo);

    @PostMapping("/withdraw/cancel")
    Response<Boolean> cancelWithdrawRequest(@RequestParam("customerNo") String customerNo,
                                             @RequestParam("requestNo") String requestNo,
                                             @RequestParam(value = "cancelReason", required = false) String cancelReason);

    @PostMapping("/withdraw/list")
    Response<Map<String, Object>> queryWithdrawRequests(@RequestBody WithdrawRequestQueryDTO query,
                                                          @RequestParam("pageNum") int pageNum,
                                                          @RequestParam("pageSize") int pageSize);

    // ==================== 内部查询（供 drone-system 直接查询支付数据） ====================

    @GetMapping("/payment/latest-by-plan/{planNo}")
    Response<PaymentOrder> getLatestPaymentOrderByPlanNo(@PathVariable("planNo") String planNo);

    @GetMapping("/refund/list-by-order/{orderNo}")
    Response<List<Refund>> getRefundsByOrderNo(@PathVariable("orderNo") String orderNo);

    @GetMapping("/refund/latest-pending-by-order/{orderNo}")
    Response<Refund> getLatestPendingRefundByOrderNo(@PathVariable("orderNo") String orderNo);

    @GetMapping("/wallet/transactions/raw/{customerNo}")
    Response<Map<String, Object>> getTransactionsByCustomerNo(
            @PathVariable("customerNo") String customerNo,
            @RequestParam(value = "transactionType", required = false) String transactionType,
            @RequestParam("pageNum") Integer pageNum,
            @RequestParam("pageSize") Integer pageSize);

    @GetMapping("/wallet/transactions/statistics/{customerNo}")
    Response<List<WalletTransaction>> getIncomeStatistics(
            @PathVariable("customerNo") String customerNo,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime);

    // ==================== 管理端 ====================

    @PostMapping("/admin/wallets")
    Response<Map<String, Object>> queryWallets(@RequestBody WalletAdminQueryDTO query,
                                                @RequestParam("pageNum") Integer pageNum,
                                                @RequestParam("pageSize") Integer pageSize);

    @PostMapping("/admin/transactions")
    Response<Map<String, Object>> queryTransactions(@RequestBody TransactionQueryDTO query,
                                                      @RequestParam("pageNum") Integer pageNum,
                                                      @RequestParam("pageSize") Integer pageSize);

    @PostMapping("/admin/reconciliation/tasks")
    Response<Map<String, Object>> queryReconciliationTasks(@RequestBody ReconciliationQueryDTO query,
                                                             @RequestParam("pageNum") int pageNum,
                                                             @RequestParam("pageSize") int pageSize);

    @PostMapping("/admin/reconciliation/create")
    Response<Void> createReconciliationTask(@RequestParam("date") String date,
                                              @RequestParam("channel") String channel);
}
