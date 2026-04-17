package club.beenest.payment.feign;

import club.beenest.payment.common.Response;
import club.beenest.payment.object.dto.*;
import club.beenest.payment.object.entity.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
            public Response<Map<String, Object>> getTransactionHistory(String customerNo, String bizType,
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

            // ==================== 充值 / 支付 ====================

            @Override
            public Response<Map<String, Object>> createRechargeOrder(String customerNo, RechargeRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> createOrderPayment(String customerNo, OrderPaymentRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> queryPaymentStatus(String customerNo, String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> queryPaymentStatusForAdmin(String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Boolean> cancelRechargeOrder(String customerNo, String orderNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> queryOrders(PaymentOrderQueryDTO query, int pageNum, int pageSize) {
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
            public Response<Map<String, Object>> queryRefunds(RefundQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> syncRefundStatus(String refundNo) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> syncProcessingRefunds(int limit) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> auditRefund(Long id, String status, String remark) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 提现 ====================

            @Override
            public Response<Map<String, Object>> createWithdrawRequest(String customerNo, WithdrawRequestDTO request) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> getWithdrawRequestStatus(String customerNo, String requestNo) {
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
            public Response<Map<String, Object>> queryWithdrawRequests(WithdrawRequestQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 内部查询 ====================

            @Override
            public Response<PaymentOrder> getLatestPaymentOrderByPlanNo(String planNo) {
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
            public Response<Map<String, Object>> getTransactionsByCustomerNo(String customerNo, String transactionType,
                                                                              Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<List<WalletTransaction>> getIncomeStatistics(String customerNo, String startTime, String endTime) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            // ==================== 管理端 ====================

            @Override
            public Response<Map<String, Object>> queryWallets(WalletAdminQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> queryTransactions(TransactionQueryDTO query, Integer pageNum, Integer pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Map<String, Object>> queryReconciliationTasks(ReconciliationQueryDTO query, int pageNum, int pageSize) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public Response<Void> createReconciliationTask(String date, String channel) {
                return Response.fail(503, "支付服务暂不可用，请稍后重试");
            }
        };
    }
}
