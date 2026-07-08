package club.beenest.payment.feign;

import club.beenest.payment.common.Response;
import club.beenest.payment.common.AdminPageResult;
import club.beenest.payment.paymentorder.dto.BatchSyncResultDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentResultDTO;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.PaymentEventQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundApplyDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundSyncResultDTO;
import club.beenest.payment.paymentorder.entity.PaymentOrder;
import club.beenest.payment.paymentorder.entity.PaymentEvent;
import club.beenest.payment.paymentorder.entity.Refund;
import club.beenest.payment.wallet.dto.WalletBalanceDTO;
import club.beenest.payment.wallet.dto.WalletAdminQueryDTO;
import club.beenest.payment.wallet.dto.TransactionQueryDTO;
import club.beenest.payment.wallet.dto.TransactionHistoryDTO;
import club.beenest.payment.wallet.entity.Wallet;
import club.beenest.payment.wallet.entity.WalletTransaction;
import club.beenest.payment.withdraw.dto.WithdrawRequestDTO;
import club.beenest.payment.withdraw.dto.WithdrawRequestQueryDTO;
import club.beenest.payment.withdraw.dto.WithdrawResultDTO;
import club.beenest.payment.withdraw.dto.WithdrawAuditDTO;
import club.beenest.payment.withdraw.entity.WithdrawRequest;
import club.beenest.payment.payscore.dto.CreditCheckResultDTO;
import club.beenest.payment.payscore.dto.ServiceOrderCreateDTO;
import club.beenest.payment.payscore.dto.ServiceOrderResultDTO;
import club.beenest.payment.reconciliation.dto.ReconciliationQueryDTO;
import club.beenest.payment.reconciliation.entity.ReconciliationTask;
import club.beenest.payment.shared.entity.PaymentChannelConfig;
import club.beenest.payment.shared.entity.RiskRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * PaymentFeignClient 降级工厂
 * 当 beenest-payment 服务不可用时，提供兜底响应
 *
 * <p>降级策略：记录日志 + 返回服务降级提示，避免调用方收到 500 错误。</p>
 */
@Slf4j
@Component
public class PaymentFeignFallbackFactory implements FallbackFactory<PaymentFeignClient> {

    @Override
    public PaymentFeignClient create(Throwable cause) {
        log.error("支付服务调用降级 - {}", cause.getMessage(), cause);

        return new PaymentFeignClient() {

            // ==================== 钱包操作 ====================

            @Override
            public Response<BigDecimal> getBalance(String customerNo, String bizType) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<WalletBalanceDTO> getWalletBalance(String customerNo, String bizType) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Wallet> getWallet(String customerNo, String bizType) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Wallet> createWallet(String customerNo, String bizType) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<TransactionHistoryDTO>> getTransactionHistory(String customerNo, String bizType,
                                                                        Integer pageNum, Integer pageSize,
                                                                        String transactionType) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> addBalance(String customerNo, String bizType, BigDecimal amount,
                                              String description, String transactionType, String referenceNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> deductBalance(String customerNo, String bizType, BigDecimal amount,
                                                    String description, String transactionType, String referenceNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> freezeBalance(String customerNo, String bizType, Long amount,
                                                    String description, String referenceNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> unfreezeBalance(String customerNo, String bizType, Long amount,
                                                      String description, String referenceNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 充值 / 支付 ====================

            @Override
            public Response<OrderPaymentResultDTO> createRechargeOrder(String customerNo, RechargeRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<OrderPaymentResultDTO> createOrderPayment(String customerNo, OrderPaymentRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<PaymentStatusDTO> queryPaymentStatus(String customerNo, String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<PaymentStatusDTO> queryPaymentStatusForAdmin(String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> cancelRechargeOrder(String customerNo, String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<PaymentOrder>> queryOrders(PaymentOrderQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 退款 ====================

            @Override
            public Response<Refund> applyRefund(String orderNo, Long amount, String reason) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Refund> requestRefundReview(String orderNo, Long amount, String reason) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<Refund>> queryRefunds(RefundQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<RefundSyncResultDTO> syncRefundStatus(String refundNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<BatchSyncResultDTO> syncProcessingRefunds(int limit) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> auditRefund(Long id, String status, String remark) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 提现 ====================

            @Override
            public Response<WithdrawResultDTO> createWithdrawRequest(String customerNo, WithdrawRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<WithdrawResultDTO> getWithdrawRequestStatus(String customerNo, String requestNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> auditWithdrawRequest(String requestNo, boolean approved, String auditUser, String auditRemark) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> processWithdrawRequest(String requestNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> cancelWithdrawRequest(String customerNo, String requestNo, String cancelReason) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<WithdrawRequest>> queryWithdrawRequests(WithdrawRequestQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 内部查询 ====================

            @Override
            public Response<PaymentOrder> getLatestPaymentOrderByBizNo(String bizNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<List<Refund>> getRefundsByOrderNo(String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Refund> getLatestPendingRefundByOrderNo(String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<WalletTransaction>> getTransactionsByCustomerNo(String customerNo, String transactionType,
                                                                              Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<List<WalletTransaction>> getIncomeStatistics(String customerNo, String startTime, String endTime) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 管理端 ====================

            @Override
            public Response<AdminPageResult<Wallet>> queryWallets(WalletAdminQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<TransactionHistoryDTO>> queryTransactions(TransactionQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<ReconciliationTask>> queryReconciliationTasks(ReconciliationQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> createReconciliationTask(String date, String channel) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 支付分 - 信用免押 ====================

            @Override
            public Response<CreditCheckResultDTO> checkCreditEligibility(String customerNo, String platform, Long depositAmount) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            @Override
            public Response<ServiceOrderResultDTO> createServiceOrder(String customerNo, ServiceOrderCreateDTO request) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            @Override
            public Response<ServiceOrderResultDTO> completeServiceOrder(String orderNo, Long actualAmount) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> cancelServiceOrder(String orderNo) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            @Override
            public Response<ServiceOrderResultDTO> queryServiceOrderStatus(String orderNo) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            @Override
            public Response<ServiceOrderResultDTO> getLatestServiceOrderByBizNo(String bizNo) {
                return Response.fail(503, "支付分服务暂不可用，请稍后重试");
            }

            // ==================== 管理端（BFF 代理使用，强类型返回） ====================

            @Override
            public Response<AdminPageResult<PaymentOrder>> adminQueryOrders(PaymentOrderQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<PaymentStatusDTO> adminSyncOrder(String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Refund> adminApplyRefund(RefundApplyDTO params) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<Refund>> adminQueryRefunds(RefundQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminAuditRefund(Long id, String status, String remark) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<Wallet>> adminQueryWallets(WalletAdminQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<TransactionHistoryDTO>> adminQueryTransactions(TransactionQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<WithdrawRequest>> adminQueryWithdraws(WithdrawRequestQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminAuditWithdraw(WithdrawAuditDTO audit) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<ReconciliationTask>> adminQueryReconciliation(ReconciliationQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminCreateReconciliationTask(String date, String channel) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<AdminPageResult<PaymentEvent>> adminQueryEvents(PaymentEventQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminReplayEvent(Long id) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<List<RiskRule>> adminGetRiskRules() {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminCreateRiskRule(RiskRule rule) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminUpdateRiskRule(RiskRule rule) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminDeleteRiskRule(Long id) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<List<PaymentChannelConfig>> adminGetConfigs() {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> adminUpdateConfig(PaymentChannelConfig config) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }
        };
    }
}
