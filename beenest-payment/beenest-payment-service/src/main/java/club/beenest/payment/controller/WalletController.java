package club.beenest.payment.controller;

import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.Response;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.object.dto.RechargeRequestDTO;
import club.beenest.payment.object.dto.TransactionHistoryDTO;
import club.beenest.payment.object.dto.WalletBalanceDTO;
import club.beenest.payment.object.dto.WithdrawRequestDTO;
import club.beenest.payment.service.IPaymentService;
import club.beenest.payment.service.IWalletService;
import club.beenest.payment.service.IWithdrawService;
import club.beenest.payment.strategy.PaymentStrategy;
import club.beenest.payment.strategy.PaymentStrategyFactory;
import com.github.pagehelper.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 钱包控制器
 * 提供钱包相关的REST API接口
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>余额查询 - 查询用户钱包余额信息</li>
 *   <li>交易历史 - 查询用户交易记录</li>
 *   <li>充值功能 - 创建充值订单和处理支付</li>
 *   <li>提现功能 - 创建提现申请和处理</li>
 *   <li>红包兑换 - 红包余额转换为钱包余额</li>
 * </ul>
 *
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>用户身份验证</li>
 *   <li>参数验证和过滤</li>
 *   <li>操作日志记录</li>
 *   <li>异常统一处理</li>
 * </ul>
 *
 * @author System
 * @since 2026-01-26
 */
@Tag(name = "钱包管理", description = "钱包相关API接口")
@RestController
@RequestMapping("/api/wallet")
@Validated
@Slf4j
public class WalletController {

    @Autowired
    private IWalletService walletService;

    @Autowired
    private IPaymentService paymentService;

    @Autowired
    private IWithdrawService withdrawService;

    @Autowired
    private PaymentStrategyFactory paymentStrategyFactory;

    // ==================== 余额查询 ====================

    /**
     * 查询钱包余额信息
     *
     * <p>查询用户钱包的完整余额信息，包括可用余额、红包余额、优惠券数量等。</p>
     *
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>balance - 可用余额（分）</li>
     *   <li>frozenBalance - 冻结余额（分）</li>
     *   <li>redPacketBalance - 红包余额（分）</li>
     *   <li>couponCount - 可用优惠券数量</li>
     *   <li>totalRecharge - 累计充值金额（分）</li>
     *   <li>totalWithdraw - 累计提现金额（分）</li>
     *   <li>totalConsume - 累计消费金额（分）</li>
     * </ul>
     *
     * @return 钱包余额信息
     */
    @Operation(summary = "查询钱包余额", description = "查询用户钱包的完整余额信息")
    @GetMapping("/balance")
    @LogAudit
    public Response<WalletBalanceDTO> getWalletBalance() {
        try {
            String customerNo = AuthUtils.requireCurrentUserId();

            WalletBalanceDTO balanceInfo = walletService.getWalletBalance(customerNo, null);

            return Response.success(balanceInfo);

        } catch (IllegalArgumentException e) {
            log.warn("查询钱包余额参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("查询钱包余额失败：{}", e.getMessage(), e);
            return Response.fail(500, "查询余额失败，请稍后重试");
        }
    }

    // ==================== 交易历史 ====================

    /**
     * 查询交易历史记录
     *
     * <p>分页查询用户的交易历史记录，支持按交易类型过滤。</p>
     *
     * <h4>查询参数：</h4>
     * <ul>
     *   <li>pageNum - 页码，从1开始，默认为1</li>
     *   <li>pageSize - 每页大小，默认为20，最大为100</li>
     *   <li>transactionType - 交易类型过滤，可选</li>
     * </ul>
     *
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>分页信息 - 总记录数、总页数、当前页等</li>
     *   <li>交易列表 - 交易流水号、类型、金额、时间等</li>
     * </ul>
     *
     * @param pageNum         页码，从1开始
     * @param pageSize        每页大小，最大100
     * @param transactionType 交易类型过滤，可选
     * @return 分页的交易历史记录
     */
    @Operation(summary = "查询交易历史", description = "分页查询用户的交易历史记录")
    @GetMapping("/transactions")
    @LogAudit
    public Response<Page<TransactionHistoryDTO>> getTransactionHistory(
        @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,
        @Parameter(description = "每页大小，最大100") @RequestParam(defaultValue = "20") @Min(1) Integer pageSize,
        @Parameter(description = "交易类型过滤") @RequestParam(required = false) String transactionType) {

        try {
            // 限制页面大小
            if (pageSize > 100) {
                pageSize = 100;
            }

            String customerNo = AuthUtils.requireCurrentUserId();

            Page<TransactionHistoryDTO> transactionHistory = walletService.getTransactionHistory(
                customerNo, null, pageNum, pageSize, transactionType);

            return Response.success(transactionHistory);

        } catch (IllegalArgumentException e) {
            log.warn("查询交易历史参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("查询交易历史失败：{}", e.getMessage(), e);
            return Response.fail(500, "查询交易历史失败，请稍后重试");
        }
    }

    // ==================== 充值功能 ====================

    /**
     * 创建充值订单
     *
     * <p>创建充值订单并返回支付参数，支持微信、支付宝、抖音等多平台支付。</p>
     *
     * <h4>支持平台：</h4>
     * <ul>
     *   <li>WECHAT - 微信支付</li>
     *   <li>ALIPAY - 支付宝</li>
     *   <li>DOUYIN - 抖音支付</li>
     * </ul>
     *
     * <h4>请求参数：</h4>
     * <ul>
     *   <li>amount - 充值金额（分），最小100分（1元）</li>
     *   <li>platform - 支付平台</li>
     *   <li>paymentMethod - 支付方式（可选）</li>
     *   <li>returnUrl - 支付完成跳转地址（可选）</li>
     * </ul>
     *
     * <h4>返回信息：</h4>
     * <ul>
     *   <li>订单号 - 用于查询订单状态</li>
     *   <li>支付参数 - 前端调用支付所需的参数</li>
     *   <li>过期时间 - 订单过期时间</li>
     * </ul>
     *
     * @param rechargeRequest 充值请求参数
     * @return 充值订单信息和支付参数
     */
    @Operation(summary = "创建充值订单", description = "创建充值订单并返回支付参数")
    @PostMapping("/recharge")
    @LogAudit
    public Response<Map<String, Object>> createRechargeOrder(
        @Parameter(description = "充值请求参数") @Valid @RequestBody RechargeRequestDTO rechargeRequest) {

        try {
            String customerNo = AuthUtils.requireCurrentUserId();

            // 验证充值金额范围
            if (!rechargeRequest.isValidAmount()) {
                return Response.fail(400, "充值金额不在有效范围内");
            }

            // 验证支付平台
            if (!rechargeRequest.isValidPlatform()) {
                return Response.fail(400, "不支持的支付平台");
            }

            // 创建充值订单
            Map<String, Object> orderInfo = paymentService.createRechargeOrder(customerNo, rechargeRequest);

            return Response.success(orderInfo);

        } catch (IllegalArgumentException e) {
            log.warn("创建充值订单参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("创建充值订单失败：{}", e.getMessage(), e);
            return Response.fail(500, "创建充值订单失败，请稍后重试");
        }
    }

    /**
     * 支付回调处理
     *
     * <p>处理第三方支付平台的支付结果回调通知。</p>
     *
     * <h4>处理流程：</h4>
     * <ol>
     *   <li>验证回调签名</li>
     *   <li>查询订单状态</li>
     *   <li>更新订单状态</li>
     *   <li>增加用户余额</li>
     *   <li>记录交易流水</li>
     *   <li>返回处理结果</li>
     * </ol>
     *
     * @param platform 支付平台
     * @param request  HTTP请求对象，包含回调数据
     * @return 处理结果，返回给支付平台
     */
    @Operation(summary = "支付回调处理", description = "处理第三方支付平台的回调通知")
    @PostMapping("/payment/callback/{platform}")
    public String handlePaymentCallback(
        @Parameter(description = "支付平台") @PathVariable @NotBlank String platform,
        HttpServletRequest request) {

        try {
            log.info("收到支付回调：平台={}", platform);

            // 处理支付回调
            boolean success = paymentService.handlePaymentCallback(platform, request);

            if (success) {
                // 返回成功响应给支付平台
                return getSuccessCallbackResponse(platform);
            } else {
                // 返回失败响应给支付平台
                return getFailureCallbackResponse(platform);
            }

        } catch (Exception e) {
            log.error("处理支付回调失败：平台={}, 错误={}", platform, e.getMessage(), e);
            return getFailureCallbackResponse(platform);
        }
    }

    @Operation(summary = "退款回调处理", description = "处理第三方支付平台的退款回调通知")
    @PostMapping("/payment/refund/callback/{platform}")
    public String handleRefundCallback(
        @Parameter(description = "支付平台") @PathVariable @NotBlank String platform,
        HttpServletRequest request) {

        try {
            log.info("收到退款回调：平台={}", platform);

            boolean success = paymentService.handleRefundCallback(platform, request);
            if (success) {
                return getSuccessCallbackResponse(platform);
            }
            return getFailureCallbackResponse(platform);
        } catch (Exception e) {
            log.error("处理退款回调失败：平台={}, 错误={}", platform, e.getMessage(), e);
            return getFailureCallbackResponse(platform);
        }
    }

    // ==================== 提现功能 ====================

    /**
     * 创建提现申请
     *
     * <p>创建用户提现申请，支持支付宝和银行卡提现。</p>
     *
     * <h4>提现类型：</h4>
     * <ul>
     *   <li>ALIPAY - 支付宝提现</li>
     *   <li>BANK_CARD - 银行卡提现</li>
     * </ul>
     *
     * <h4>请求参数：</h4>
     * <ul>
     *   <li>amount - 提现金额（分），最小10000分（100元）</li>
     *   <li>withdrawType - 提现类型</li>
     *   <li>accountType - 账户类型（个人/企业）</li>
     *   <li>accountName - 账户姓名</li>
     *   <li>accountNumber - 账户号码</li>
     *   <li>bankName - 银行名称（银行卡提现必填）</li>
     *   <li>bankBranch - 开户行支行（可选）</li>
     * </ul>
     *
     * <h4>业务规则：</h4>
     * <ul>
     *   <li>用户同时只能有一个处理中的提现申请</li>
     *   <li>提现金额不能超过可用余额</li>
     *   <li>需要扣除相应的手续费</li>
     * </ul>
     *
     * @param withdrawRequest 提现请求参数
     * @return 提现申请结果
     */
    @Operation(summary = "创建提现申请", description = "创建用户提现申请")
    @PostMapping("/withdraw")
    @LogAudit
    public Response<Map<String, Object>> createWithdrawRequest(
        @Parameter(description = "提现请求参数") @Valid @RequestBody WithdrawRequestDTO withdrawRequest) {

        try {
            String customerNo = AuthUtils.requireCurrentUserId();

            // 验证提现金额范围
            if (!withdrawRequest.isValidAmount()) {
                return Response.fail(400, "提现金额不在有效范围内");
            }

            // 验证提现类型
            if (!withdrawRequest.isValidWithdrawType()) {
                return Response.fail(400, "不支持的提现类型");
            }

            // 验证账户类型
            if (!withdrawRequest.isValidAccountType()) {
                return Response.fail(400, "无效的账户类型");
            }

            // 验证银行卡提现必填字段
            if (!withdrawRequest.validateBankCardFields()) {
                return Response.fail(400, "银行卡提现时银行名称不能为空");
            }

            // 验证账户号码格式
            if (!withdrawRequest.isValidAccountNumber()) {
                return Response.fail(400, "账户号码格式不正确");
            }

            // 创建提现申请
            Map<String, Object> withdrawInfo = withdrawService.createWithdrawRequest(customerNo, withdrawRequest);

            return Response.success(withdrawInfo);

        } catch (IllegalArgumentException e) {
            log.warn("创建提现申请参数错误：{}", e.getMessage());
            return Response.fail(400, "参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.error("创建提现申请失败：{}", e.getMessage(), e);
            return Response.fail(500, "创建提现申请失败，请稍后重试");
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取支付成功回调响应
     */
    private String getSuccessCallbackResponse(String platform) {
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(platform);
            return strategy.getSuccessResponse();
        } catch (Exception e) {
            log.warn("获取成功回调响应失败，使用默认兜底 - platform: {}, error: {}", platform, e.getMessage());
            return "SUCCESS";
        }
    }

    /**
     * 获取支付失败回调响应
     */
    private String getFailureCallbackResponse(String platform) {
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(platform);
            return strategy.getFailureResponse();
        } catch (Exception e) {
            log.warn("获取失败回调响应失败，使用默认兜底 - platform: {}, error: {}", platform, e.getMessage());
            return "FAILURE";
        }
    }
}
