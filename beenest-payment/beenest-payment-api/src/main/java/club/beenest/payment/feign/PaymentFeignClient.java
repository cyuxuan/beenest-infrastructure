package club.beenest.payment.feign;

import club.beenest.payment.common.AdminPageResult;
import club.beenest.payment.common.Response;
import club.beenest.payment.paymentorder.dto.BatchSyncResultDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentResultDTO;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentEventQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RefundApplyDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundSyncResultDTO;
import club.beenest.payment.wallet.dto.WalletBalanceDTO;
import club.beenest.payment.wallet.dto.WalletAdminQueryDTO;
import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.withdraw.dto.WithdrawResultDTO;
import club.beenest.payment.withdraw.dto.WithdrawAuditDTO;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;
import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.paymentorder.entity.PaymentOrder;
import club.beenest.payment.paymentorder.entity.PaymentEvent;
import club.beenest.payment.paymentorder.entity.Refund;
import club.beenest.payment.shared.dto.CreateAppCredentialDTO;
import club.beenest.payment.shared.dto.UpdateAppCredentialDTO;
import club.beenest.payment.shared.entity.AppCredential;
import club.beenest.payment.shared.entity.PaymentChannelConfig;
import club.beenest.payment.shared.entity.RiskRule;
import club.beenest.payment.shared.vo.AppCredentialVO;
import club.beenest.payment.wallet.entity.Wallet;
import club.beenest.payment.wallet.entity.WalletTransaction;
import club.beenest.payment.withdraw.entity.WithdrawRequest;
import club.beenest.payment.reconciliation.entity.ReconciliationTask;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

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
    Response<BigDecimal> getBalance(@PathVariable("customerNo") String customerNo);

    @GetMapping("/wallet/detail/{customerNo}")
    Response<WalletBalanceDTO> getWalletBalance(@PathVariable("customerNo") String customerNo);

    @GetMapping("/wallet/{customerNo}")
    Response<Wallet> getWallet(@PathVariable("customerNo") String customerNo);

    @PostMapping("/wallet/create/{customerNo}")
    Response<Wallet> createWallet(@PathVariable("customerNo") String customerNo);

    @GetMapping("/wallet/transactions/{customerNo}")
    Response<AdminPageResult<TransactionHistoryDTO>> getTransactionHistory(
            @PathVariable("customerNo") String customerNo,
            @RequestParam("pageNum") Integer pageNum,
            @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "transactionType", required = false) String transactionType);

    @PostMapping("/wallet/add-balance")
    Response<Void> addBalance(@RequestParam("customerNo") String customerNo,
                              @RequestParam("amount") BigDecimal amount,
                              @RequestParam("description") String description,
                              @RequestParam("transactionType") String transactionType,
                              @RequestParam(value = "referenceNo", required = false) String referenceNo);

    @PostMapping("/wallet/deduct-balance")
    Response<Boolean> deductBalance(@RequestParam("customerNo") String customerNo,
                                    @RequestParam("amount") BigDecimal amount,
                                    @RequestParam("description") String description,
                                    @RequestParam("transactionType") String transactionType,
                                    @RequestParam(value = "referenceNo", required = false) String referenceNo);

    @PostMapping("/wallet/freeze-balance")
    Response<Boolean> freezeBalance(@RequestParam("customerNo") String customerNo,
                                    @RequestParam("amount") Long amount,
                                    @RequestParam("description") String description,
                                    @RequestParam(value = "referenceNo", required = false) String referenceNo);

    @PostMapping("/wallet/unfreeze-balance")
    Response<Boolean> unfreezeBalance(@RequestParam("customerNo") String customerNo,
                                      @RequestParam("amount") Long amount,
                                      @RequestParam("description") String description,
                                      @RequestParam(value = "referenceNo", required = false) String referenceNo);

    // ==================== 充值 / 支付 ====================

    @PostMapping("/payment/recharge")
    Response<OrderPaymentResultDTO> createRechargeOrder(@RequestParam("customerNo") String customerNo,
                                                       @RequestBody RechargeRequestDTO request);

    @PostMapping("/payment/order-payment")
    Response<OrderPaymentResultDTO> createOrderPayment(@RequestParam("customerNo") String customerNo,
                                                      @RequestBody OrderPaymentRequestDTO request);

    @GetMapping("/payment/status/{orderNo}")
    Response<PaymentStatusDTO> queryPaymentStatus(@RequestParam("customerNo") String customerNo,
                                                      @PathVariable("orderNo") String orderNo);

    @GetMapping("/payment/status-admin/{orderNo}")
    Response<PaymentStatusDTO> queryPaymentStatusForAdmin(@PathVariable("orderNo") String orderNo);

    @PostMapping("/payment/cancel/{orderNo}")
    Response<Boolean> cancelRechargeOrder(@RequestParam("customerNo") String customerNo,
                                           @PathVariable("orderNo") String orderNo);

    @PostMapping("/payment/orders")
    Response<AdminPageResult<PaymentOrder>> queryOrders(@RequestBody PaymentOrderQueryDTO query,
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
    Response<AdminPageResult<Refund>> queryRefunds(@RequestBody RefundQueryDTO query,
                                                @RequestParam("pageNum") int pageNum,
                                                @RequestParam("pageSize") int pageSize);

    @GetMapping("/refund/sync/{refundNo}")
    Response<RefundSyncResultDTO> syncRefundStatus(@PathVariable("refundNo") String refundNo);

    @PostMapping("/refund/sync-processing")
    Response<BatchSyncResultDTO> syncProcessingRefunds(@RequestParam("limit") int limit);

    @PostMapping("/refund/audit")
    Response<Void> auditRefund(@RequestParam("id") Long id,
                                @RequestParam("status") String status,
                                @RequestParam("remark") String remark);

    // ==================== 提现 ====================

    @PostMapping("/withdraw/create")
    Response<WithdrawResultDTO> createWithdrawRequest(@RequestParam("customerNo") String customerNo,
                                                          @RequestBody WithdrawRequestDTO request);

    @GetMapping("/withdraw/status/{requestNo}")
    Response<WithdrawResultDTO> getWithdrawRequestStatus(@RequestParam("customerNo") String customerNo,
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
    Response<AdminPageResult<WithdrawRequest>> queryWithdrawRequests(@RequestBody WithdrawRequestQueryDTO query,
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
    Response<AdminPageResult<WalletTransaction>> getTransactionsByCustomerNo(
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
    Response<AdminPageResult<Wallet>> queryWallets(@RequestBody WalletAdminQueryDTO query,
                                                @RequestParam("pageNum") Integer pageNum,
                                                @RequestParam("pageSize") Integer pageSize);

    @PostMapping("/admin/transactions")
    Response<AdminPageResult<TransactionHistoryDTO>> queryTransactions(@RequestBody TransactionQueryDTO query,
                                                      @RequestParam("pageNum") Integer pageNum,
                                                      @RequestParam("pageSize") Integer pageSize);

    @PostMapping("/admin/reconciliation/tasks")
    Response<AdminPageResult<ReconciliationTask>> queryReconciliationTasks(@RequestBody ReconciliationQueryDTO query,
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

    // ==================== 管理端（BFF 代理使用，强类型返回） ====================

    /**
     * 管理端-分页查询支付订单
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/orders/page")
    Response<AdminPageResult<PaymentOrder>> adminQueryOrders(@RequestBody PaymentOrderQueryDTO query,
                                                              @RequestParam("pageNum") int pageNum,
                                                              @RequestParam("pageSize") int pageSize);

    /**
     * 管理端-同步订单状态
     *
     * @param orderNo 订单号
     * @return 同步后的支付状态
     */
    @PostMapping("/admin/orders/{orderNo}/sync")
    Response<PaymentStatusDTO> adminSyncOrder(@PathVariable("orderNo") String orderNo);

    /**
     * 管理端-申请退款
     *
     * @param params 退款申请参数
     * @return 退款记录
     */
    @PostMapping("/admin/refunds/apply")
    Response<Refund> adminApplyRefund(@RequestBody RefundApplyDTO params);

    /**
     * 管理端-分页查询退款记录
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/refunds/page")
    Response<AdminPageResult<Refund>> adminQueryRefunds(@RequestBody RefundQueryDTO query,
                                                         @RequestParam("pageNum") int pageNum,
                                                         @RequestParam("pageSize") int pageSize);

    /**
     * 管理端-审核退款
     *
     * @param id 退款ID
     * @param status 审核状态
     * @param remark 审核备注
     */
    @PostMapping("/admin/refunds/audit")
    Response<Void> adminAuditRefund(@RequestParam("id") Long id,
                                    @RequestParam("status") String status,
                                    @RequestParam("remark") String remark);

    /**
     * 管理端-分页查询钱包
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/wallets/page")
    Response<AdminPageResult<Wallet>> adminQueryWallets(@RequestBody WalletAdminQueryDTO query,
                                                         @RequestParam("pageNum") Integer pageNum,
                                                         @RequestParam("pageSize") Integer pageSize);

    /**
     * 管理端-分页查询交易流水
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/transactions/page")
    Response<AdminPageResult<TransactionHistoryDTO>> adminQueryTransactions(@RequestBody TransactionQueryDTO query,
                                                                            @RequestParam("pageNum") Integer pageNum,
                                                                            @RequestParam("pageSize") Integer pageSize);

    /**
     * 管理端-分页查询提现申请
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/withdraws/page")
    Response<AdminPageResult<WithdrawRequest>> adminQueryWithdraws(@RequestBody WithdrawRequestQueryDTO query,
                                                                    @RequestParam("pageNum") int pageNum,
                                                                    @RequestParam("pageSize") int pageSize);

    /**
     * 管理端-审核提现申请
     *
     * @param audit 审核参数
     */
    @PostMapping("/admin/withdraws/audit")
    Response<Void> adminAuditWithdraw(@RequestBody WithdrawAuditDTO audit);

    /**
     * 管理端-分页查询对账任务
     *
     * @param query 查询条件
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/reconciliation/page")
    Response<AdminPageResult<ReconciliationTask>> adminQueryReconciliation(@RequestBody ReconciliationQueryDTO query,
                                                                            @RequestParam("pageNum") int pageNum,
                                                                            @RequestParam("pageSize") int pageSize);

    /**
     * 管理端-创建对账任务
     *
     * @param date 对账日期
     * @param channel 支付渠道
     */
    @PostMapping("/admin/reconciliation/create")
    Response<Void> adminCreateReconciliationTask(@RequestParam("date") String date,
                                                  @RequestParam("channel") String channel);

    /**
     * 管理端-分页查询支付事件
     *
     * @param query 查询条件（含 bizType）
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    @PostMapping("/admin/events/page")
    Response<AdminPageResult<PaymentEvent>> adminQueryEvents(@RequestBody PaymentEventQueryDTO query,
                                                              @RequestParam("pageNum") int pageNum,
                                                              @RequestParam("pageSize") int pageSize);

    /**
     * 管理端-重试支付事件
     *
     * @param id 事件ID
     */
    @PostMapping("/admin/events/{id}/replay")
    Response<Void> adminReplayEvent(@PathVariable("id") Long id);

    /**
     * 管理端-查询风控规则
     *
     * @return 风控规则列表
     */
    @GetMapping("/admin/risk/rules")
    Response<List<RiskRule>> adminGetRiskRules();

    /**
     * 管理端-创建风控规则
     *
     * @param rule 风控规则
     */
    @PostMapping("/admin/risk/rules/create")
    Response<Void> adminCreateRiskRule(@RequestBody RiskRule rule);

    /**
     * 管理端-更新风控规则
     *
     * @param rule 风控规则
     */
    @PostMapping("/admin/risk/rules/update")
    Response<Void> adminUpdateRiskRule(@RequestBody RiskRule rule);

    /**
     * 管理端-删除风控规则
     *
     * @param id 规则ID
     */
    @PostMapping("/admin/risk/rules/delete/{id}")
    Response<Void> adminDeleteRiskRule(@PathVariable("id") Long id);

    /**
     * 管理端-查询支付渠道配置
     *
     * @return 配置列表
     */
    @GetMapping("/admin/configs/list")
    Response<List<PaymentChannelConfig>> adminGetConfigs();

    /**
     * 管理端-更新支付渠道配置
     *
     * @param config 配置信息
     */
    @PostMapping("/admin/configs/update")
    Response<Void> adminUpdateConfig(@RequestBody PaymentChannelConfig config);

    // ==================== 应用凭证管理 ====================

    /**
     * 列表查询应用凭证（密钥脱敏）
     *
     * @return 凭证列表
     */
    @GetMapping("/app-credential/list")
    Response<List<AppCredentialVO>> listAppCredentials();

    /**
     * 查询单个应用凭证（密钥脱敏）
     *
     * @param appId 业务系统标识
     * @return 凭证信息
     */
    @GetMapping("/app-credential/{appId}")
    Response<AppCredentialVO> getAppCredential(@PathVariable("appId") String appId);

    /**
     * 创建应用凭证（返回原始密钥，仅此一次）
     *
     * @param dto 创建参数
     * @return 创建的凭证（含明文密钥）
     */
    @PostMapping("/app-credential/create")
    Response<AppCredential> createAppCredential(@RequestBody CreateAppCredentialDTO dto);

    /**
     * 更新应用信息（名称、IP白名单、描述）
     *
     * @param dto 更新参数
     */
    @PostMapping("/app-credential/update")
    Response<Void> updateAppCredential(@RequestBody UpdateAppCredentialDTO dto);

    /**
     * 轮换 app_secret（令牌认证 + HMAC 签名共用，返回新明文密钥，仅此一次）
     *
     * @param appId 业务系统标识
     * @return 新的明文密钥
     */
    @PostMapping("/app-credential/rotate-secret/{appId}")
    Response<String> rotateAppSecret(@PathVariable("appId") String appId);

    /**
     * 轮换 mq_secret（返回新明文密钥，仅此一次）
     *
     * @param appId 业务系统标识
     * @return 新的明文密钥
     */
    @PostMapping("/app-credential/rotate-mq-secret/{appId}")
    Response<String> rotateMqSecret(@PathVariable("appId") String appId);

    /**
     * 启用应用
     *
     * @param appId 业务系统标识
     */
    @PostMapping("/app-credential/enable/{appId}")
    Response<Void> enableAppCredential(@PathVariable("appId") String appId);

    /**
     * 禁用应用
     *
     * @param appId 业务系统标识
     */
    @PostMapping("/app-credential/disable/{appId}")
    Response<Void> disableAppCredential(@PathVariable("appId") String appId);
}
