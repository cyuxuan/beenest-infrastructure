package club.beenest.payment.feign;

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
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;
import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.paymentorder.entity.PaymentOrder;
import club.beenest.payment.paymentorder.entity.Refund;
import club.beenest.payment.wallet.entity.Wallet;
import club.beenest.payment.wallet.entity.WalletTransaction;
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

    @PostMapping("/wallet/freeze-balance")
    Response<Boolean> freezeBalance(@RequestParam("customerNo") String customerNo,
                                    @RequestParam(value = "bizType", required = false) String bizType,
                                    @RequestParam("amount") Long amount,
                                    @RequestParam("description") String description,
                                    @RequestParam(value = "referenceNo", required = false) String referenceNo);

    @PostMapping("/wallet/unfreeze-balance")
    Response<Boolean> unfreezeBalance(@RequestParam("customerNo") String customerNo,
                                      @RequestParam(value = "bizType", required = false) String bizType,
                                      @RequestParam("amount") Long amount,
                                      @RequestParam("description") String description,
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

    @GetMapping("/payment/latest-by-biz-no/{bizNo}")
    Response<PaymentOrder> getLatestPaymentOrderByBizNo(@PathVariable("bizNo") String bizNo);

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

    // ==================== 支付分 - 信用免押 ====================

    /**
     * 信用免押检查
     *
     * @param customerNo 用户/商户编号
     * @param platform 支付分平台（WECHAT_PAYSCORE / ALIPAY_ZHIMA）
     * @param depositAmount 保证金金额（分）
     * @return 免押检查结果
     */
    @PostMapping("/payscore/check-credit")
    Response<CreditCheckResultDTO> checkCreditEligibility(
            @RequestParam("customerNo") String customerNo,
            @RequestParam("platform") String platform,
            @RequestParam("depositAmount") Long depositAmount);

    /**
     * 创建服务订单（发起信用免押授权）
     *
     * @param customerNo 用户/商户编号
     * @param request 创建服务订单请求
     * @return 服务订单结果（含授权跳转参数）
     */
    @PostMapping("/payscore/create")
    Response<ServiceOrderResultDTO> createServiceOrder(
            @RequestParam("customerNo") String customerNo,
            @RequestBody ServiceOrderCreateDTO request);

    /**
     * 完结服务订单（扣取实际费用，解冻剩余额度）
     *
     * @param orderNo 服务订单号
     * @param actualAmount 实际扣款金额（分，0表示全额解冻）
     * @return 服务订单结果
     */
    @PostMapping("/payscore/complete/{orderNo}")
    Response<ServiceOrderResultDTO> completeServiceOrder(
            @PathVariable("orderNo") String orderNo,
            @RequestParam("actualAmount") Long actualAmount);

    /**
     * 取消服务订单（取消授权，解冻额度）
     *
     * @param orderNo 服务订单号
     * @return 是否取消成功
     */
    @PostMapping("/payscore/cancel/{orderNo}")
    Response<Boolean> cancelServiceOrder(@PathVariable("orderNo") String orderNo);

    /**
     * 查询服务订单状态
     *
     * @param orderNo 服务订单号
     * @return 服务订单结果
     */
    @GetMapping("/payscore/status/{orderNo}")
    Response<ServiceOrderResultDTO> queryServiceOrderStatus(@PathVariable("orderNo") String orderNo);

    /**
     * 根据业务单号查询最新服务订单
     *
     * @param bizNo 业务单号
     * @return 服务订单结果
     */
    @GetMapping("/payscore/latest-by-biz-no/{bizNo}")
    Response<ServiceOrderResultDTO> getLatestServiceOrderByBizNo(@PathVariable("bizNo") String bizNo);
}
