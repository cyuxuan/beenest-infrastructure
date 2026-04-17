package club.beenest.payment.service.impl;

import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.constant.PaymentRedisKeyConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import club.beenest.payment.constant.BizTypeConstants;
import club.beenest.payment.constant.PaymentConstants;
import club.beenest.payment.constant.PaymentRetryConstants;
import club.beenest.payment.object.enums.PaymentEventStatus;
import club.beenest.payment.object.enums.PaymentEventType;
import club.beenest.payment.object.enums.PaymentOrderStatus;
import club.beenest.payment.object.enums.RefundStatus;
import club.beenest.payment.object.enums.WalletTransactionType;
import club.beenest.payment.util.PaymentValidateUtils;
import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.mq.producer.PaymentEventProducer;
import club.beenest.payment.mq.message.PaymentOrderCompletedMessage;
import club.beenest.payment.mq.message.RefundCompletedMessage;
import club.beenest.payment.config.PaymentConfig;
import club.beenest.payment.mapper.PaymentEventMapper;
import club.beenest.payment.mapper.PaymentOrderMapper;
import club.beenest.payment.mapper.RefundMapper;
import club.beenest.payment.mapper.WalletMapper;
import club.beenest.payment.object.dto.OrderPaymentRequestDTO;
import club.beenest.payment.object.dto.PaymentOrderQueryDTO;
import club.beenest.payment.object.dto.RechargeRequestDTO;
import club.beenest.payment.object.dto.RefundQueryDTO;
import club.beenest.payment.object.entity.PaymentEvent;
import club.beenest.payment.object.entity.PaymentOrder;
import club.beenest.payment.object.entity.Refund;
import club.beenest.payment.object.enums.OrderRefundPolicy;
import club.beenest.payment.object.entity.Wallet;
import club.beenest.payment.service.IPaymentService;
import club.beenest.payment.service.IWalletService;
import club.beenest.payment.strategy.PaymentStrategy;
import club.beenest.payment.strategy.PaymentStrategyFactory;
import club.beenest.payment.common.utils.MoneyUtil;
import club.beenest.payment.common.utils.TradeNoGenerator;
import club.beenest.payment.common.utils.TransactionSynchronizationUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 支付服务实现类（重构版）
 * 使用策略模式和工厂模式实现支付功能
 * 
 * <p>
 * 设计模式：
 * </p>
 * <ul>
 * <li>策略模式 - 每个支付平台作为独立策略</li>
 * <li>工厂模式 - 通过工厂获取支付策略</li>
 * <li>模板方法模式 - 定义支付流程骨架</li>
 * </ul>
 * 
 * <h3>优势：</h3>
 * <ul>
 * <li>易扩展 - 新增支付平台只需实现PaymentStrategy接口</li>
 * <li>易维护 - 每个支付平台的逻辑独立，互不影响</li>
 * <li>符合开闭原则 - 对扩展开放，对修改关闭</li>
 * <li>符合单一职责原则 - 每个类只负责一个支付平台</li>
 * </ul>
 * 
 * @author System
 * @since 2026-01-26
 */
@Service
@Slf4j
public class PaymentServiceImpl implements IPaymentService {

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    @Autowired
    private PaymentEventMapper paymentEventMapper;

    @Autowired
    private WalletMapper walletMapper;

    @Autowired
    private RefundMapper refundMapper;

    @Autowired
    private IWalletService walletService;

    @Autowired
    private PaymentConfig paymentConfig;

    @Autowired
    private PaymentStrategyFactory paymentStrategyFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PaymentEventProducer paymentEventProducer;

    /**
     * 专用定时线程池，用于退款状态补偿查询
     * 替代 CompletableFuture.delayedExecutor，避免使用 ForkJoinPool
     */
    private final ScheduledExecutorService paymentAsyncExecutor =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "payment-refund-sync");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    public void shutdownExecutor() {
        log.info("正在关闭退款状态同步线程池...");
        paymentAsyncExecutor.shutdown();
        try {
            if (!paymentAsyncExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                paymentAsyncExecutor.shutdownNow();
                log.warn("退款状态同步线程池强制关闭");
            }
        } catch (InterruptedException e) {
            paymentAsyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 创建充值订单
     * 
     * <p>
     * 使用策略模式，根据支付平台选择对应的支付策略。
     * </p>
     * 
     * <h4>流程：</h4>
     * <ol>
     * <li>验证参数</li>
     * <li>查询用户钱包</li>
     * <li>生成订单号</li>
     * <li>创建订单记录</li>
     * <li>获取支付策略</li>
     * <li>调用策略创建支付</li>
     * <li>更新订单支付参数</li>
     * <li>返回支付参数</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "创建充值订单")
    public Map<String, Object> createRechargeOrder(String customerNo, RechargeRequestDTO rechargeRequest) {
        log.info("创建充值订单 - customerNo: {}, amount: {}, platform: {}",
                customerNo, rechargeRequest.getAmount(), rechargeRequest.getPlatform());

        try {
            // 1. 验证参数
            validateRechargeRequest(customerNo, rechargeRequest);

            // 2. 查询用户钱包（多租户：充值订单默认使用 DRONE_ORDER 钱包）
            String walletBizType = BizTypeConstants.DEFAULT;
            Wallet wallet = walletMapper.selectByCustomerNoAndBizType(customerNo, walletBizType);
            if (wallet == null) {
                log.error("用户钱包不存在 - customerNo: {}, bizType: {}", customerNo, walletBizType);
                throw new IllegalArgumentException("用户钱包不存在");
            }

            // 3. 生成订单号
            String orderNo = generateUniqueOrderNo();

            // 4. 创建订单记录
            PaymentOrder paymentOrder = buildPaymentOrder(orderNo, customerNo, wallet.getWalletNo(), rechargeRequest);
            int insertResult = paymentOrderMapper.insert(paymentOrder);
            if (insertResult != 1) {
                log.error("创建充值订单失败 - orderNo: {}", orderNo);
                throw new BusinessException("创建充值订单失败");
            }

            log.info("充值订单创建成功 - orderNo: {}", orderNo);

            // 5. 获取支付策略
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(rechargeRequest.getPlatform());

            // 6. 调用策略创建支付
            Map<String, Object> paymentParams = paymentStrategy.createPayment(paymentOrder);

            // 7. 更新订单支付参数
            try {
                String paymentParamsJson = objectMapper.writeValueAsString(paymentParams);
                paymentOrder.setPaymentParams(paymentParamsJson);
                paymentOrderMapper.updateByOrderNo(paymentOrder);
            } catch (Exception e) {
                log.warn("保存支付参数失败 - orderNo: {}, error: {}", orderNo, e.getMessage());
            }

            // 8. 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("orderNo", orderNo);
            result.put("amount", rechargeRequest.getAmount());
            result.put("platform", rechargeRequest.getPlatform());
            result.put("platformName", paymentStrategy.getPlatformName());
            result.put("expireTime", paymentOrder.getExpireTime());
            result.put("paymentParams", paymentParams);

            log.info("充值订单创建完成 - orderNo: {}", orderNo);
            return result;

        } catch (IllegalArgumentException e) {
            log.warn("创建充值订单参数错误 - customerNo: {}, error: {}", customerNo, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("创建充值订单失败 - customerNo: {}, error: {}", customerNo, e.getMessage(), e);
            throw new BusinessException("创建充值订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 处理支付回调
     * 
     * <p>
     * 使用策略模式，根据支付平台选择对应的支付策略处理回调。
     * </p>
     * 
     * <h4>流程：</h4>
     * <ol>
     * <li>解析回调数据</li>
     * <li>获取支付策略</li>
     * <li>验证回调签名</li>
     * <li>解析回调数据</li>
     * <li>查询订单</li>
     * <li>幂等性检查</li>
     * <li>校验金额</li>
     * <li>更新订单状态</li>
     * <li>增加用户余额</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "处理支付回调")
    public boolean handlePaymentCallback(String platform, HttpServletRequest request) {
        log.info("收到支付回调 - platform: {}", platform);

        PaymentEvent event = new PaymentEvent();
        try {
            // 1. 解析回调数据
            Map<String, String> callbackData = parseCallbackData(request);
            log.debug("回调原始数据 - platform: {}, data: {}", platform, callbackData);
            log.info("收到支付回调 - platform: {}", platform);

            // 记录支付事件
            event.setEventNo(TradeNoGenerator.generateTransactionNo());
            event.setEventTypeEnum(PaymentEventType.CALLBACK);
            event.setChannel(platform);
            event.setStatusEnum(PaymentEventStatus.PENDING);
            event.setRequestContent(objectMapper.writeValueAsString(callbackData));
            paymentEventMapper.insert(event);

            // 2. 获取支付策略
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(platform);

            // 3. 验证回调签名
            if (!paymentStrategy.verifyCallback(callbackData)) {
                log.error("回调签名验证失败 - platform: {}", platform);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Signature verification failed");
                paymentEventMapper.update(event);
                return false;
            }

            // 4. 解析回调数据
            Map<String, Object> parsedData = paymentStrategy.parseCallback(callbackData);
            String orderNo = (String) parsedData.get("orderNo");
            String transactionNo = (String) parsedData.get("transactionNo");
            Long paidAmount = (Long) parsedData.get("amount");
            String paidStatus = stringValue(parsedData.get("status"));

            event.setOrderNo(orderNo);

            if (!StringUtils.hasText(orderNo)) {
                log.error("回调数据中未找到订单号 - platform: {}", platform);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("OrderNo not found in callback data");
                paymentEventMapper.update(event);
                return false;
            }

            // 5. 查询订单
            PaymentOrder paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
            if (paymentOrder == null) {
                log.error("订单不存在 - orderNo: {}", orderNo);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Order not found");
                paymentEventMapper.update(event);
                return false;
            }

            // 6. 幂等性检查
            if (paymentOrder.isPaid()) {
                log.info("订单已支付，跳过处理 - orderNo: {}", orderNo);
                event.setStatusEnum(PaymentEventStatus.SUCCESS);
                event.setResponseContent("Idempotent check: Already paid");
                paymentEventMapper.update(event);
                return true;
            }

            if (!paymentOrder.isPending()) {
                log.warn("订单状态异常 - orderNo: {}, status: {}", orderNo, paymentOrder.getStatus());
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Invalid order status: " + paymentOrder.getStatus());
                paymentEventMapper.update(event);
                return false;
            }

            // 7. 校验金额（金额为空同样拒绝，防止构造缺失 amount 字段的伪造回调）
            if (paidAmount == null || !paidAmount.equals(paymentOrder.getAmount())) {
                log.error("支付金额校验失败 - orderNo: {}, expected: {}, actual: {}",
                        orderNo, paymentOrder.getAmount(), paidAmount);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent(paidAmount == null ? "Amount missing in callback" : "Amount mismatch");
                paymentEventMapper.update(event);
                return false;
            }

            if (!PaymentConstants.PAYMENT_STATUS_PAID.equalsIgnoreCase(paidStatus)) {
                log.warn("支付回调状态非成功，忽略入账 - orderNo: {}, callbackStatus: {}", orderNo, paidStatus);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Callback status is not PAID: " + paidStatus);
                paymentEventMapper.update(event);
                return false;
            }

            // 8. 更新订单状态
            String callbackDataJson = objectMapper.writeValueAsString(callbackData);
            int updateResult = paymentOrderMapper.updateStatusIfCurrentStatus(
                    orderNo,
                    PaymentOrderStatus.PENDING.getCode(),
                    PaymentOrderStatus.PAID.getCode(),
                    callbackDataJson,
                    transactionNo);

            if (updateResult != 1) {
                PaymentOrder latestOrder = paymentOrderMapper.selectByOrderNo(orderNo);
                if (latestOrder != null && latestOrder.isPaid()) {
                    log.info("订单已由其他流程更新为已支付 - orderNo: {}", orderNo);
                    event.setStatusEnum(PaymentEventStatus.SUCCESS);
                    event.setResponseContent("Idempotent settlement");
                    paymentEventMapper.update(event);
                    return true;
                }
                log.error("更新订单状态失败 - orderNo: {}", orderNo);
                throw new BusinessException("更新订单状态失败");
            }

            // 9. 按订单类型做后续处理（充值订单入钱包；计划订单更新计划状态）
            if (isOrderPlanPayment(paymentOrder)) {
                markPlanPaymentSuccess(paymentOrder);
            } else {
                String referenceNo = PaymentConstants.RECHARGE_PREFIX + orderNo;
                BigDecimal amountInYuan = paymentOrder.getAmountInYuan();
                walletService.addBalance(
                        paymentOrder.getCustomerNo(),
                        paymentOrder.getBizType(),
                        amountInYuan,
                        "充值到账 - 订单号：" + orderNo,
                        PaymentConstants.TRANSACTION_TYPE_RECHARGE,
                        referenceNo);
            }

            log.info("支付回调处理成功 - orderNo: {}, amount: {}", orderNo, paymentOrder.getAmount());
            event.setStatusEnum(PaymentEventStatus.SUCCESS);
            event.setResponseContent(PaymentConstants.CALLBACK_SUCCESS);
            paymentEventMapper.update(event);
            return true;

        } catch (Exception e) {
            log.error("处理支付回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            safeMarkPaymentEventFailed(event, e);
            // 【安全关键】必须重新抛出异常以触发@Transactional回滚
            // 如果订单状态已更新为PAID但addBalance失败，吞掉异常会导致事务提交，
            // 造成订单已支付但余额未到账的资金不一致问题
            throw new BusinessException("处理支付回调失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefundCallback(String platform, HttpServletRequest request) {
        log.info("收到退款回调 - platform: {}", platform);

        try {
            Map<String, String> callbackData = parseCallbackData(request);
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(platform);
            if (!paymentStrategy.verifyRefundCallback(callbackData)) {
                log.error("退款回调签名验证失败 - platform: {}", platform);
                return false;
            }

            Map<String, Object> parsedData = paymentStrategy.parseRefundCallback(callbackData);
            String refundNo = stringValue(parsedData.get("refundNo"));
            String refundId = stringValue(parsedData.get("refundId"));
            if (!StringUtils.hasText(refundNo)) {
                log.warn("退款回调缺少退款单号，尝试按第三方退款单号匹配 - platform: {}, refundId: {}", platform, refundId);
            }

            Refund refund = StringUtils.hasText(refundNo) ? refundMapper.selectByRefundNo(refundNo) : null;
            if (refund == null && StringUtils.hasText(refundId)) {
                refund = refundMapper.selectByThirdPartyRefundNo(refundId);
            }
            if (refund == null) {
                log.error("退款单不存在 - refundNo: {}, refundId: {}", refundNo, refundId);
                return false;
            }
            if (refund.getStatusEnum() == RefundStatus.SUCCESS || refund.getStatusEnum() == RefundStatus.REJECTED) {
                log.info("退款单已到终态，忽略重复回调 - refundNo: {}, status: {}", refundNo, refund.getStatus());
                return true;
            }

            PaymentOrder order = paymentOrderMapper.selectByOrderNo(refund.getOrderNo());
            if (order == null) {
                log.error("退款原支付订单不存在 - refundNo: {}, orderNo: {}", refundNo, refund.getOrderNo());
                return false;
            }

            applyRefundSyncResult(order, refund, parsedData, PaymentConstants.CALLBACK_SOURCE);
            refund.setAuditRemark(firstNonBlank(refund.getAuditRemark(), "渠道退款回调更新"));
            refundMapper.update(refund);
            return true;
        } catch (Exception e) {
            log.error("处理退款回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            throw new BusinessException("处理退款回调失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询订单支付状态
     * 
     * <p>
     * 使用策略模式，根据支付平台选择对应的支付策略查询状态。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> queryPaymentStatus(String customerNo, String orderNo) {
        log.info("查询订单支付状态 - orderNo: {}", orderNo);

        try {
            // 1. 参数验证
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }

            // 2. 查询本地订单
            PaymentOrder paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
            if (paymentOrder == null) {
                throw new IllegalArgumentException("订单不存在");
            }
            if (StringUtils.hasText(customerNo) && !customerNo.equals(paymentOrder.getCustomerNo())) {
                throw new IllegalArgumentException("无权查看该订单");
            }

            // 3. 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("orderNo", orderNo);
            result.put("status", paymentOrder.getStatus());
            result.put("amount", paymentOrder.getAmount());
            result.put("platform", paymentOrder.getPlatform());
            result.put("createTime", paymentOrder.getCreateTime());
            result.put("paidTime", paymentOrder.getPaidTime());
            result.put("expireTime", paymentOrder.getExpireTime());
            result.put("planNo", paymentOrder.getPlanNo());

            // 4. 对待支付订单主动向支付渠道补偿查询，兜住回调丢失场景
            if (paymentOrder.isPending()) {
                try {
                    PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(paymentOrder.getPlatform());
                    Map<String, Object> platformStatus = paymentStrategy.queryPayment(paymentOrder);
                    if (platformStatus != null) {
                        syncPaymentOrderFromQuery(paymentOrder, platformStatus);
                        paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
                        result.put("status", paymentOrder.getStatus());
                        result.put("paidTime", paymentOrder.getPaidTime());
                        result.putAll(platformStatus);
                    }
                } catch (Exception e) {
                    log.warn("查询支付平台状态失败 - orderNo: {}, error: {}", orderNo, e.getMessage());
                }
            }

            log.info("查询订单支付状态成功 - orderNo: {}, status: {}", orderNo, paymentOrder.getStatus());
            return result;

        } catch (IllegalArgumentException e) {
            log.warn("查询订单支付状态参数错误 - orderNo: {}, error: {}", orderNo, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("查询订单支付状态失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            throw new BusinessException("查询订单支付状态失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> queryPaymentStatusForAdmin(String orderNo) {
        return queryPaymentStatus(null, orderNo);
    }

    /**
     * 取消充值订单
     * 
     * <p>
     * 使用策略模式，根据支付平台选择对应的支付策略取消订单。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "取消充值订单")
    public boolean cancelRechargeOrder(String customerNo, String orderNo) {
        log.info("取消充值订单 - customerNo: {}, orderNo: {}", customerNo, orderNo);

        try {
            // 1. 参数验证
            if (!StringUtils.hasText(customerNo)) {
                throw new IllegalArgumentException("用户编号不能为空");
            }
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }

            // 2. 查询订单
            PaymentOrder paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
            if (paymentOrder == null) {
                throw new IllegalArgumentException("订单不存在");
            }

            // 3. 验证用户权限
            if (!customerNo.equals(paymentOrder.getCustomerNo())) {
                throw new IllegalArgumentException("无权操作该订单");
            }

            // 4. 检查订单状态
            if (!paymentOrder.canCancel()) {
                throw new IllegalArgumentException("订单状态不允许取消");
            }

            // 5. 调用支付平台取消订单（如果支持）
            try {
                PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(paymentOrder.getPlatform());
                paymentStrategy.cancelPayment(paymentOrder);
            } catch (Exception e) {
                log.warn("调用支付平台取消订单失败 - orderNo: {}, error: {}", orderNo, e.getMessage());
            }

            // 6. 更新订单状态
            int updateResult = paymentOrderMapper.updateStatus(orderNo, PaymentOrderStatus.CANCELLED.getCode(), null,
                    null);
            if (updateResult != 1) {
                log.error("取消订单失败 - orderNo: {}", orderNo);
                throw new BusinessException("取消订单失败");
            }

            if (isOrderPlanPayment(paymentOrder)) {
                rollbackPlanPaymentState(paymentOrder);
            }

            log.info("取消充值订单成功 - orderNo: {}", orderNo);
            return true;

        } catch (IllegalArgumentException e) {
            log.warn("取消充值订单参数错误 - customerNo: {}, orderNo: {}, error: {}",
                    customerNo, orderNo, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("取消充值订单失败 - customerNo: {}, orderNo: {}, error: {}",
                    customerNo, orderNo, e.getMessage(), e);
            throw new BusinessException("取消充值订单失败：" + e.getMessage(), e);
        }
    }

    /**
     * 创建订单支付（直接支付模式）
     * 通用化改造：不查询任何业务实体，金额由调用方传入
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "客户支付", operation = "创建订单支付")
    public Map<String, Object> createOrderPayment(String customerNo, OrderPaymentRequestDTO request) {
        log.info("创建订单支付 - customerNo: {}, planNo: {}, amount: {}, payType: {}",
                customerNo, request.getPlanNo(), request.getAmount(), request.getPayType());

        try {
            validateOrderPaymentRequest(customerNo, request);

            String backendPlatform = request.getBackendPlatform();
            String bizNo = request.getPlanNo();

            // 金额计算：调用方传入amount（最终支付金额），originalAmount/discountAmount用于展示
            Long finalAmount = request.getAmount();
            Long baseAmount = request.getOriginalAmount() != null ? request.getOriginalAmount() : finalAmount;
            Long discountAmount = request.getDiscountAmount() != null ? request.getDiscountAmount() : 0L;

            // 如果有优惠券，计算折扣
            CouponDiscountResult discountResult = calculateCouponDiscount(customerNo, request, baseAmount);
            if (discountResult.discountAmount() > 0) {
                discountAmount = discountResult.discountAmount();
                finalAmount = Math.max(baseAmount - discountAmount, 0L);
            }

            validatePaymentAmount(finalAmount, request.getAmount());

            // 检查是否有待支付订单
            PaymentOrder latestPending = paymentOrderMapper.selectLatestPendingByPlanNo(bizNo);
            if (latestPending != null && latestPending.canPay()) {
                return handleExistingPendingOrder(latestPending, backendPlatform, finalAmount,
                        baseAmount, discountAmount, discountResult.userCouponNo(), bizNo);
            }

            return createNewOrderPayment(customerNo, bizNo, request.getBizType(), backendPlatform, finalAmount,
                    baseAmount, discountAmount, discountResult.userCouponNo(), request.getOpenid());

        } catch (IllegalArgumentException e) {
            log.warn("创建订单支付参数错误 - customerNo: {}, error: {}", customerNo, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("创建订单支付失败 - customerNo: {}, error: {}", customerNo, e.getMessage(), e);
            throw new BusinessException("创建订单支付失败：" + e.getMessage(), e);
        }
    }

    private void validateOrderPaymentRequest(String customerNo, OrderPaymentRequestDTO request) {
        PaymentValidateUtils.notBlank(customerNo, "用户编号不能为空");
        PaymentValidateUtils.notBlank(request.getPlanNo(), "业务单号不能为空");
        String backendPlatform = request.getBackendPlatform();
        PaymentValidateUtils.isTrue(paymentStrategyFactory.isEnabled(backendPlatform),
                () -> "支付平台未启用: " + request.getPayType());
    }

    private CouponDiscountResult calculateCouponDiscount(String customerNo, OrderPaymentRequestDTO request,
            Long baseAmountFen) {
        // 优惠券逻辑已迁出支付中台，由调用方直接传入优惠金额
        Long discountAmount = request.getDiscountAmount() != null ? request.getDiscountAmount() : 0L;
        return new CouponDiscountResult(discountAmount, null);
    }

    private void validatePaymentAmount(Long finalAmount, Long requestAmount) {
        PaymentValidateUtils.isTrue(finalAmount != null && finalAmount > 0, "支付金额必须大于0");
        PaymentValidateUtils.isTrue(requestAmount == null || requestAmount.equals(finalAmount),
                "支付金额校验失败，请刷新后重试");
    }

    private Map<String, Object> handleExistingPendingOrder(PaymentOrder existingOrder, String backendPlatform,
            Long finalAmount, Long baseAmount, Long discountAmount, String usedCouponNo, String planNo) {
        String existingPlatform = existingOrder.getPlatform();

        if (!backendPlatform.equalsIgnoreCase(existingPlatform)) {
            throw new IllegalArgumentException("存在待支付订单（支付方式不一致），请继续支付或先取消后重试");
        }
        if (!finalAmount.equals(existingOrder.getAmount())) {
            throw new IllegalArgumentException("存在待支付订单（金额不一致），请继续支付或先取消后重试");
        }

        PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(backendPlatform);
        Map<String, Object> paymentParams = getOrCreatePaymentParams(existingOrder, paymentStrategy);

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", existingOrder.getOrderNo());
        result.put("planNo", planNo);
        result.put("amount", finalAmount);
        result.put("originalAmount", baseAmount);
        result.put("discountAmount", discountAmount);
        result.put("platform", backendPlatform);
        result.put("platformName", paymentStrategy.getPlatformName());
        result.put("expireTime", existingOrder.getExpireTime());
        result.put("paymentParams", paymentParams);

        log.info("复用待支付订单 - orderNo: {}, planNo: {}", existingOrder.getOrderNo(), planNo);
        return result;
    }

    private Map<String, Object> getOrCreatePaymentParams(PaymentOrder order, PaymentStrategy strategy) {
        if (StringUtils.hasText(order.getPaymentParams())) {
            try {
                return objectMapper.readValue(order.getPaymentParams(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("解析支付参数失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
            }
        }
        Map<String, Object> paymentParams = strategy.createPayment(order);
        try {
            String paymentParamsJson = objectMapper.writeValueAsString(paymentParams);
            order.setPaymentParams(paymentParamsJson);
            paymentOrderMapper.updateByOrderNo(order);
        } catch (Exception e) {
            log.warn("保存支付参数失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
        }
        return paymentParams;
    }

    private Map<String, Object> createNewOrderPayment(String customerNo, String bizNo, String bizType,
            String backendPlatform, Long finalAmount, Long baseAmount, Long discountAmount,
            String usedCouponNo, String openid) {
        String walletBizType = BizTypeConstants.DEFAULT;
        Wallet wallet = walletMapper.selectByCustomerNoAndBizType(customerNo, walletBizType);
        String walletNo = wallet != null ? wallet.getWalletNo() : "";

        String orderNo = generateUniqueOrderNo();
        LocalDateTime now = LocalDateTime.now();
        int expireMinutes = paymentConfig.getCommon().getOrderExpireMinutes();
        LocalDateTime expireTime = now.plusMinutes(expireMinutes);

        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setOrderNo(orderNo);
        paymentOrder.setCustomerNo(customerNo);
        paymentOrder.setWalletNo(walletNo);
        paymentOrder.setAmount(finalAmount);
        paymentOrder.setPlatform(backendPlatform);
        paymentOrder.setPaymentMethod(PaymentConstants.getPaymentMethod(backendPlatform));
        paymentOrder.setStatusEnum(PaymentOrderStatus.PENDING);
        paymentOrder.setExpireTime(expireTime);
        paymentOrder.setNotifyUrl(getNotifyUrl(backendPlatform));
        paymentOrder.setPlanNo(bizNo);
        // 将openid存入returnUrl字段，供微信支付策略提取
        if (StringUtils.hasText(openid)) {
            paymentOrder.setReturnUrl("openid:" + openid);
        }
        paymentOrder.setRemark(buildOrderPlanRemark(bizNo, usedCouponNo));
        paymentOrder.setCreateTime(now);
        paymentOrder.setUpdateTime(now);

        int insertResult = paymentOrderMapper.insert(paymentOrder);
        if (insertResult != 1) {
            throw new BusinessException("创建支付订单失败");
        }

        // 订单创建后无需发MQ，业务系统是调用方本身已知
        // 支付成功/取消/过期时才发MQ通知

        String expireKey = PaymentRedisKeyConstants.buildPaymentOrderExpireKey(orderNo);
        stringRedisTemplate.opsForValue().set(expireKey, orderNo, java.time.Duration.ofMinutes(expireMinutes));
        log.info("设置支付订单过期Key - orderNo: {}, expireMinutes: {}", orderNo, expireMinutes);

        PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(backendPlatform);
        Map<String, Object> paymentParams = paymentStrategy.createPayment(paymentOrder);

        try {
            String paymentParamsJson = objectMapper.writeValueAsString(paymentParams);
            paymentOrder.setPaymentParams(paymentParamsJson);
            paymentOrderMapper.updateByOrderNo(paymentOrder);
        } catch (Exception e) {
            log.warn("保存支付参数失败 - orderNo: {}, error: {}", orderNo, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", orderNo);
        result.put("planNo", bizNo);
        result.put("amount", finalAmount);
        result.put("originalAmount", baseAmount);
        result.put("discountAmount", discountAmount);
        result.put("platform", backendPlatform);
        result.put("platformName", paymentStrategy.getPlatformName());
        result.put("expireTime", paymentOrder.getExpireTime());
        result.put("paymentParams", paymentParams);

        log.info("订单支付创建完成 - orderNo: {}, bizNo: {}", orderNo, bizNo);
        return result;
    }

    private record CouponDiscountResult(Long discountAmount, String userCouponNo) {}

    /**
     * 验证优惠券并计算折扣（通用化版本，使用金额而非OrderPlan）
     */
    private boolean isOrderPlanPayment(PaymentOrder paymentOrder) {
        return paymentOrder != null && StringUtils.hasText(paymentOrder.getPlanNo());
    }

    private String extractPlanNoFromPaymentOrder(PaymentOrder paymentOrder) {
        if (paymentOrder == null) {
            return null;
        }
        return paymentOrder.getPlanNo();
    }

    private String buildOrderPlanRemark(String planNo, String userCouponNo) {
        return PaymentConstants.ORDER_PLAN_PREFIX + planNo;
    }

    private void markPlanPaymentSuccess(PaymentOrder paymentOrder) {
        String planNo = extractPlanNoFromPaymentOrder(paymentOrder);
        if (StringUtils.hasText(planNo)) {
            final PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
            msg.setOrderNo(paymentOrder.getOrderNo());
            msg.setBusinessOrderNo(planNo);
            msg.setCustomerNo(paymentOrder.getCustomerNo());
            msg.setAmountFen(paymentOrder.getAmount());
            msg.setPlatform(paymentOrder.getPlatform());
            msg.setPaidAt(paymentOrder.getPaidTime() != null ? paymentOrder.getPaidTime().toString() : LocalDateTime.now().toString());

            TransactionSynchronizationUtils.afterCommit(() -> {
                paymentEventProducer.sendOrderCompleted(msg);
                log.info("事务提交后发送支付成功MQ消息 - orderNo: {}, bizNo: {}", msg.getOrderNo(), planNo);
            });
        }
    }

    private void rollbackPlanPaymentState(PaymentOrder paymentOrder) {
        String planNo = paymentOrder.getPlanNo();
        if (!StringUtils.hasText(planNo)) {
            return;
        }
        final PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
        msg.setOrderNo(paymentOrder.getOrderNo());
        msg.setBusinessOrderNo(planNo);
        msg.setCustomerNo(paymentOrder.getCustomerNo());
        msg.setAmountFen(paymentOrder.getAmount());
        msg.setPlatform(paymentOrder.getPlatform());

        TransactionSynchronizationUtils.afterCommit(() -> {
            paymentEventProducer.sendOrderCancelled(msg);
            log.info("事务提交后发送订单取消MQ消息 - orderNo: {}, bizNo: {}", msg.getOrderNo(), planNo);
        });
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 验证充值请求参数
     */
    private void validateRechargeRequest(String customerNo, RechargeRequestDTO rechargeRequest) {
        PaymentValidateUtils.notBlank(customerNo, "用户编号不能为空");
        PaymentValidateUtils.notNull(rechargeRequest, "充值请求参数不能为空");
        PaymentValidateUtils.isTrue(rechargeRequest.isValidAmount(), "充值金额不在有效范围内");
        PaymentValidateUtils.isTrue(rechargeRequest.isValidPlatform(), "不支持的支付平台");
        PaymentValidateUtils.isTrue(paymentStrategyFactory.isEnabled(rechargeRequest.getPlatform()), "支付平台未启用");
        
        Long amountFen = rechargeRequest.getAmount();
        PaymentValidateUtils.inRange(amountFen, 
                PaymentConstants.MIN_RECHARGE_AMOUNT_FEN, 
                PaymentConstants.MAX_RECHARGE_AMOUNT_FEN,
                "充值金额需在1元至10万元之间");
    }

    /**
     * 生成唯一订单号
     * 依赖 ds_payment_order(order_no) UNIQUE 约束保证唯一性，
     * 若 INSERT 时触发 DuplicateKeyException 由调用方处理重试。
     */
    private String generateUniqueOrderNo() {
        return TradeNoGenerator.generatePaymentOrderNo();
    }

    /**
     * 构建充值订单对象
     */
    private PaymentOrder buildPaymentOrder(String orderNo, String customerNo, String walletNo,
            RechargeRequestDTO rechargeRequest) {
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setOrderNo(orderNo);
        paymentOrder.setCustomerNo(customerNo);
        paymentOrder.setWalletNo(walletNo);
        paymentOrder.setAmount(rechargeRequest.getAmount());
        paymentOrder.setPlatform(rechargeRequest.getPlatform());
        paymentOrder.setPaymentMethod(rechargeRequest.getDefaultPaymentMethod());
        paymentOrder.setStatusEnum(PaymentOrderStatus.PENDING);
        paymentOrder.setReturnUrl(rechargeRequest.getReturnUrl());
        paymentOrder.setRemark(rechargeRequest.getRemark());

        // 设置过期时间
        int expireMinutes = paymentConfig.getCommon().getOrderExpireMinutes();
        paymentOrder.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));

        // 设置回调地址
        String notifyUrl = getNotifyUrl(rechargeRequest.getPlatform());
        paymentOrder.setNotifyUrl(notifyUrl);

        LocalDateTime now = LocalDateTime.now();
        paymentOrder.setCreateTime(now);
        paymentOrder.setUpdateTime(now);

        return paymentOrder;
    }

    /**
     * 获取回调通知地址
     */
    private String getNotifyUrl(String platform) {
        return switch (platform.toUpperCase()) {
            case PaymentConstants.PLATFORM_WECHAT -> paymentConfig.getWechat().getNotifyUrl();
            case PaymentConstants.PLATFORM_ALIPAY -> paymentConfig.getAlipay().getNotifyUrl();
            case PaymentConstants.PLATFORM_DOUYIN -> paymentConfig.getDouyin().getNotifyUrl();
            default -> null;
        };
    }

    /**
     * 解析回调数据
     * 仅收集支付平台回调所需的白名单头部和请求参数/体
     */
    private Map<String, String> parseCallbackData(HttpServletRequest request) throws Exception {
        Map<String, String> data = new HashMap<>();

        // 只收集白名单内的请求头，避免泄露无关内部头信息
        List<String> whitelistHeaders = List.of(
                "Wechatpay-Signature", "Wechatpay-Timestamp", "Wechatpay-Nonce",
                "Wechatpay-Serial", "Wechatpay-Certificate",
                "Alipay-Signature", "Alipay-Timestamp", "Alipay-App-Id",
                "Alipay-Charset", "Alipay-Sign-Type", "Alipay-Notify-Type");
        for (String header : whitelistHeaders) {
            String value = request.getHeader(header);
            if (value != null) {
                data.put(header, value);
            }
        }

        // 从请求参数中获取
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                data.put(key, values[0]);
            }
        });

        // 如果是POST请求，从请求体中获取
        if (PaymentConstants.HTTP_METHOD_POST.equalsIgnoreCase(request.getMethod())) {
            try (BufferedReader reader = request.getReader()) {
                String body = reader.lines().collect(Collectors.joining());
                if (StringUtils.hasText(body)) {
                    // 尝试解析JSON
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> jsonData = objectMapper.readValue(body, Map.class);
                        jsonData.forEach((key, value) -> {
                            if (value != null) {
                                data.put(key, value.toString());
                            }
                        });
                        // 存入原始报文供签名验证使用
                        data.put("body", body);
                    } catch (Exception e) {
                        // 如果不是JSON，尝试解析为表单数据
                        String[] pairs = body.split("&");
                        for (String pair : pairs) {
                            String[] kv = pair.split("=", 2);
                            if (kv.length == 2) {
                                data.put(kv[0], kv[1]);
                            }
                        }
                    }
                }
            }
        }

        return data;
    }

    @Override
    public Page<PaymentOrder> queryOrders(PaymentOrderQueryDTO query, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return (Page<PaymentOrder>) paymentOrderMapper.selectByQuery(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "申请退款")
    public Refund applyRefund(String orderNo, Long amount, String reason) {
        log.info("申请退款（聚合贪心模式）- identifier: {}, amount: {}, reason: {}", orderNo, amount, reason);

        // 1. 先尝试直接按 orderNo 精确匹配单笔退款（兼容旧逻辑）
        PaymentOrder directOrder = paymentOrderMapper.selectByOrderNo(orderNo);
        if (directOrder != null) {
            // 单笔支付订单，走原有逻辑
            loadRefundableOrder(orderNo, amount);
            ensureNoProcessingRefund(orderNo);
            if (isOrderPlanPayment(directOrder)) {
                return handleOrderPlanRefund(directOrder, amount, reason);
            }
            Refund refund = buildRefund(orderNo, amount, reason, RefundStatus.PENDING,
                    OrderRefundPolicy.AUTO_REFUND, PaymentConstants.REFUND_SOURCE_ADMIN, PaymentConstants.SOURCE_SYSTEM);
            refundMapper.insert(refund);
            try {
                executeRefund(directOrder, refund, PaymentConstants.SOURCE_SYSTEM, "自动退款成功");
                return refund;
            } catch (Exception e) {
                log.error("退款失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
                throw new BusinessException("退款请求提交失败: " + e.getMessage(), e);
            }
        }

        // 2. 未找到直接匹配，按 planNo 聚合所有支付流水（首付 + 补差），贪心分配退款
        List<PaymentOrder> planOrders = paymentOrderMapper.selectSuccessfulByPlanNo(orderNo);
        if (planOrders == null || planOrders.isEmpty()) {
            throw new BusinessException("订单不存在或尚未支付");
        }

        // 校验可退总额
        long totalRefundable = planOrders.stream().mapToLong(po -> {
            Long alreadyRefunded = refundMapper.sumSuccessAmountByOrderNo(po.getOrderNo());
            return po.getAmount() - (alreadyRefunded != null ? alreadyRefunded : 0L);
        }).sum();

        if (amount == null || amount <= 0) throw new BusinessException("退款金额必须大于0");
        if (amount > totalRefundable) {
            throw new BusinessException("申请退款金额超限，最大可退总额：" + totalRefundable + "分");
        }

        // 贪心分配：按支付时间顺序逐笔扣减退款任务
        Refund lastRefund = null;
        long remaining = amount;
        for (PaymentOrder po : planOrders) {
            if (remaining <= 0) break;
            ensureNoProcessingRefund(po.getOrderNo());
            Long alreadyRefunded = refundMapper.sumSuccessAmountByOrderNo(po.getOrderNo());
            long available = po.getAmount() - (alreadyRefunded != null ? alreadyRefunded : 0L);
            if (available <= 0) continue;

            long segment = Math.min(remaining, available);
            remaining -= segment;

            Refund refund = buildRefund(po.getOrderNo(), segment, reason, RefundStatus.PENDING,
                    OrderRefundPolicy.AUTO_REFUND, PaymentConstants.REFUND_SOURCE_ADMIN, PaymentConstants.SOURCE_SYSTEM);
            refundMapper.insert(refund);
            try {
                executeRefund(po, refund, PaymentConstants.SOURCE_SYSTEM, "聚合贪心退款成功");
            } catch (Exception e) {
                log.error("聚合退款子任务失败 - orderNo: {}, segment: {}, error: {}", po.getOrderNo(), segment, e.getMessage(), e);
                throw new BusinessException("退款失败（流水号：" + po.getOrderNo() + "）：" + e.getMessage(), e);
            }
            lastRefund = refund;
        }
        return lastRefund;
    }

    private Refund handleOrderPlanRefund(PaymentOrder order, Long amount, String reason) {
        // 业务系统通过MQ事件获知退款结果后自行决定是否需要人工审核
        Refund refund = buildRefund(order.getOrderNo(), amount, reason, RefundStatus.PENDING,
                OrderRefundPolicy.AUTO_REFUND, PaymentConstants.REFUND_SOURCE_ADMIN, PaymentConstants.SOURCE_SYSTEM);
        refundMapper.insert(refund);

        try {
            executeRefund(order, refund, PaymentConstants.SOURCE_SYSTEM, "后台管理自动退款成功");
            return refund;
        } catch (Exception e) {
            log.error("退款失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage(), e);
            throw new BusinessException("退款请求提交失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "创建退款申请")
    public Refund requestRefundReview(String orderNo, Long amount, String reason) {
        PaymentOrder order = loadRefundableOrder(orderNo, amount);
        ensureNoProcessingRefund(orderNo);

        Refund refund = buildRefund(orderNo, amount, reason, RefundStatus.PENDING,
                OrderRefundPolicy.MANUAL_REVIEW, PaymentConstants.REFUND_SOURCE_CUSTOMER_CANCEL, order.getCustomerNo());
        refund.setAuditRemark("待人工审核");
        refundMapper.insert(refund);
        return refund;
    }

    @Override
    public Page<Refund> queryRefunds(RefundQueryDTO query, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return (Page<Refund>) refundMapper.selectByQuery(
                query.getRefundNo(),
                query.getOrderNo(),
                query.getStatus(),
                query.getRefundPolicy(),
                query.getRequestSource(),
                query.getApplicantId(),
                query.getChannelStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncRefundStatus(String refundNo) {
        if (!StringUtils.hasText(refundNo)) {
            throw new BusinessException("退款单号不能为空");
        }

        Refund refund = refundMapper.selectByRefundNo(refundNo);
        if (refund == null) {
            throw new BusinessException("退款单不存在");
        }

        PaymentOrder order = paymentOrderMapper.selectByOrderNo(refund.getOrderNo());
        if (order == null) {
            throw new BusinessException("原支付订单不存在");
        }

        if (refund.getStatusEnum() == RefundStatus.SUCCESS || refund.getStatusEnum() == RefundStatus.REJECTED) {
            return buildRefundSyncResult(refund, order, PaymentConstants.LOCAL_SOURCE);
        }

        if (shouldResubmitRefundBeforeQuery(order, refund)) {
            executeRefund(order, refund, PaymentConstants.MANUAL_SYNC_SOURCE, firstNonBlank(refund.getAuditRemark(), "补偿提交历史待处理退款"));
            return buildRefundSyncResult(refund, order, PaymentConstants.RESUBMIT_SOURCE);
        }

        PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
        Map<String, Object> channelResult;
        try {
            channelResult = strategy.queryRefund(order, refund);
        } catch (Exception e) {
            if (shouldResubmitRefundAfterQueryFailure(order, refund, e)) {
                log.warn("退款查询命中微信历史单，改为按商户退款单号补发 - refundNo: {}, error: {}",
                        refund.getRefundNo(), e.getMessage());
                refund.setThirdPartyRefundNo(null);
                executeRefund(order, refund, PaymentConstants.MANUAL_SYNC_SOURCE, firstNonBlank(refund.getAuditRemark(), "微信历史退款查询不存在，补偿重提"));
                return buildRefundSyncResult(refund, order, PaymentConstants.RESUBMIT_SOURCE);
            }
            throw e;
        }
        applyRefundSyncResult(order, refund, channelResult, PaymentConstants.MANUAL_SYNC_SOURCE);
        refundMapper.update(refund);
        return buildRefundSyncResult(refund, order, PaymentConstants.CHANNEL_SOURCE);
    }

    @Override
    public Map<String, Object> syncProcessingRefunds(int limit) {
        int effectiveLimit = Math.max(limit, 1);
        List<Refund> refunds = refundMapper.selectByStatusForSync(RefundStatus.PROCESSING.getCode(), effectiveLimit);

        int successCount = 0;
        int failedCount = 0;
        for (Refund refund : refunds) {
            try {
                Map<String, Object> result = syncRefundStatus(refund.getRefundNo());
                String status = stringValue(result.get("status"));
                if (RefundStatus.SUCCESS.getCode().equals(status)) {
                    successCount++;
                }
            } catch (Exception e) {
                failedCount++;
                log.warn("批量同步退款状态失败 - refundNo: {}, error: {}", refund.getRefundNo(), e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("requested", effectiveLimit);
        result.put("scanned", refunds.size());
        result.put("success", successCount);
        result.put("failed", failedCount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "审核退款")
    public void auditRefund(Long id, String statusStr, String remark) {
        Refund refund = refundMapper.selectById(id);
        if (refund == null) {
            throw new BusinessException("退款记录不存在");
        }

        RefundStatus currentStatus = refund.getStatusEnum();
        RefundStatus nextStatus = RefundStatus.getByCode(statusStr);

        if (nextStatus == null) {
            throw new BusinessException("无效的审核状态: " + statusStr);
        }

        if (!currentStatus.canTransitionTo(nextStatus)) {
            throw new BusinessException(
                    "退款状态流转非法: " + currentStatus.getDescription() + " -> " + nextStatus.getDescription());
        }

        if (nextStatus == RefundStatus.SUCCESS) {
            PaymentOrder order = paymentOrderMapper.selectByOrderNo(refund.getOrderNo());
            if (order == null) {
                throw new BusinessException("原支付订单不存在");
            }
            executeRefund(order, refund, PaymentConstants.REFUND_SOURCE_ADMIN, StringUtils.hasText(remark) ? remark : "审核通过并退款成功");
            return;
        }

        refund.setStatusEnum(nextStatus);
        refund.setAuditUser(PaymentConstants.REFUND_SOURCE_ADMIN);
        refund.setAuditTime(LocalDateTime.now());
        refund.setAuditRemark(remark);
        refund.setUpdateTime(LocalDateTime.now());
        refundMapper.update(refund);
    }

    private void syncPaymentOrderFromQuery(PaymentOrder paymentOrder, Map<String, Object> platformStatus) throws Exception {
        if (paymentOrder == null || platformStatus == null || !paymentOrder.isPending()) {
            return;
        }

        Object channelStatus = platformStatus.get("platformStatus");
        if (!isPlatformPaid(channelStatus == null ? null : channelStatus.toString())) {
            return;
        }

        String callbackDataJson = objectMapper.writeValueAsString(platformStatus);
        String transactionNo = stringValue(platformStatus.get("transactionId"));
        if (!StringUtils.hasText(transactionNo)) {
            transactionNo = stringValue(platformStatus.get("transactionNo"));
        }
        Long paidAmount = longValue(platformStatus.get("amount"));
        if (paidAmount != null && !paidAmount.equals(paymentOrder.getAmount())) {
            throw new BusinessException("支付金额不匹配，拒绝补记支付状态");
        }

        int updateResult = paymentOrderMapper.updateStatusIfCurrentStatus(
                paymentOrder.getOrderNo(),
                PaymentOrderStatus.PENDING.getCode(),
                PaymentOrderStatus.PAID.getCode(),
                callbackDataJson,
                transactionNo);
        if (updateResult != 1) {
            PaymentOrder latestOrder = paymentOrderMapper.selectByOrderNo(paymentOrder.getOrderNo());
            if (latestOrder != null && latestOrder.isPaid()) {
                return;
            }
            throw new BusinessException("同步支付状态失败");
        }

        if (isOrderPlanPayment(paymentOrder)) {
            markPlanPaymentSuccess(paymentOrder);
        } else {
            String referenceNo = PaymentConstants.RECHARGE_PREFIX + paymentOrder.getOrderNo();
            walletService.addBalance(
                    paymentOrder.getCustomerNo(),
                    paymentOrder.getBizType(),
                    paymentOrder.getAmountInYuan(),
                    "充值到账 - 订单号：" + paymentOrder.getOrderNo(),
                    PaymentConstants.TRANSACTION_TYPE_RECHARGE,
                    referenceNo);
        }
    }

    private boolean isPlatformPaid(String platformStatus) {
        if (!StringUtils.hasText(platformStatus)) {
            return false;
        }
        String normalized = platformStatus.trim().toUpperCase();
        return PaymentConstants.REFUND_STATUS_SUCCESS.equals(normalized)
                || PaymentConstants.REFUND_STATUS_TRADE_SUCCESS.equals(normalized)
                || PaymentConstants.REFUND_STATUS_TRADE_FINISHED.equals(normalized)
                || PaymentConstants.REFUND_STATUS_PAY_SUCCESS.equals(normalized);
    }

    private boolean shouldResubmitRefundBeforeQuery(PaymentOrder order, Refund refund) {
        if (!PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(order.getPlatform())) {
            return false;
        }
        if (StringUtils.hasText(refund.getThirdPartyRefundNo())) {
            return false;
        }
        return refund.getStatusEnum() == RefundStatus.PENDING || refund.getStatusEnum() == RefundStatus.PROCESSING;
    }

    private boolean shouldResubmitRefundAfterQueryFailure(PaymentOrder order, Refund refund, Exception e) {
        if (!PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(order.getPlatform())) {
            return false;
        }
        String message = e == null ? null : e.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toUpperCase();
        return normalized.contains(PaymentConstants.WECHAT_ERROR_RESOURCE_NOT_EXISTS) || message.contains("退款单不存在");
    }

    private PaymentOrder loadRefundableOrder(String orderNo, Long amount) {
        PaymentOrder order = paymentOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        if (order.getStatusEnum() != PaymentOrderStatus.PAID
                && order.getStatusEnum() != PaymentOrderStatus.PARTIAL_REFUNDED) {
            throw new BusinessException("订单状态不正确，无法退款");
        }
        if (amount == null || amount <= 0) {
            throw new BusinessException("退款金额必须大于0");
        }
        if (amount > order.getAmount()) {
            throw new BusinessException("退款金额不能大于订单金额");
        }
        // 校验累计退款金额：已退款总额 + 本次退款金额 <= 订单金额
        Long totalRefunded = refundMapper.sumSuccessAmountByOrderNo(orderNo);
        long alreadyRefunded = totalRefunded != null ? totalRefunded : 0L;
        if (alreadyRefunded + amount > order.getAmount()) {
            throw new BusinessException("退款金额超出可退余额，已退：" + alreadyRefunded + "分，本次：" + amount + "分，订单总额：" + order.getAmount() + "分");
        }
        return order;
    }

    private void ensureNoProcessingRefund(String orderNo) {
        List<Refund> refunds = refundMapper.selectByOrderNo(orderNo);
        if (refunds == null) {
            return;
        }
        boolean hasOpenRefund = refunds.stream()
                .map(Refund::getStatusEnum)
                .anyMatch(status -> status == RefundStatus.PENDING || status == RefundStatus.PROCESSING);
        if (hasOpenRefund) {
            throw new BusinessException("该订单已有退款申请处理中");
        }
    }

    private Refund buildRefund(String orderNo, Long amount, String reason, RefundStatus status,
            OrderRefundPolicy refundPolicy, String requestSource, String applicantId) {
        Refund refund = new Refund();
        refund.setRefundNo(TradeNoGenerator.generateRefundNo());
        refund.setOrderNo(orderNo);
        refund.setAmount(amount);
        refund.setReason(reason);
        refund.setStatusEnum(status);
        refund.setRefundPolicy(refundPolicy == null ? null : refundPolicy.getCode());
        refund.setRequestSource(requestSource);
        refund.setApplicantId(applicantId);
        refund.setCreateTime(LocalDateTime.now());
        refund.setUpdateTime(LocalDateTime.now());
        return refund;
    }

    private void executeRefund(PaymentOrder order, Refund refund, String auditUser, String auditRemark) {
        if (refund.getStatusEnum() == RefundStatus.SUCCESS) {
            return;
        }
        if (refund.getStatusEnum() == RefundStatus.PROCESSING
                && StringUtils.hasText(refund.getThirdPartyRefundNo())) {
            return;
        }

        if (!isOrderPlanPayment(order)) {
            BigDecimal refundAmountYuan = MoneyUtil.centsToYuan(refund.getAmount());
            boolean deductSuccess = walletService.deductBalance(order.getCustomerNo(), order.getBizType(), refundAmountYuan, "充值退款扣减",
                    WalletTransactionType.REFUND.getCode(), refund.getRefundNo());
            if (!deductSuccess) {
                throw new BusinessException("退款失败：用户余额不足或扣款失败");
            }
        }

        PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
        Map<String, Object> result = strategy.refund(order, refund.getAmount(), refund.getReason(), refund.getRefundNo());
        applyRefundSyncResult(order, refund, result, auditUser);
        if (refund.getStatusEnum() == RefundStatus.FAILED && !isOrderPlanPayment(order)) {
            walletService.addBalance(
                    order.getCustomerNo(),
                    order.getBizType(),
                    MoneyUtil.centsToYuan(refund.getAmount()),
                    "退款失败回退 - 退款单号：" + refund.getRefundNo(),
                    WalletTransactionType.REFUND.getCode(),
                    refund.getRefundNo() + PaymentConstants.REFUND_ROLLBACK_SUFFIX);
        }
        refund.setAuditRemark(auditRemark);
        refund.setUpdateTime(LocalDateTime.now());
        refundMapper.update(refund);

        if (refund.getStatusEnum() == RefundStatus.PROCESSING) {
            scheduleRefundStatusAutoSync(refund.getRefundNo(), 1);
        }
    }

    private void applyRefundSyncResult(PaymentOrder order, Refund refund, Map<String, Object> result, String auditUser) {
        RefundStatus mappedStatus = mapRefundStatus(order.getPlatform(), result);
        refund.setStatusEnum(mappedStatus);
        refund.setThirdPartyRefundNo(firstNonBlank(
                stringValue(result.get("refundId")),
                refund.getThirdPartyRefundNo()));
        refund.setChannelStatus(firstNonBlank(
                stringValue(result.get("channelStatus")),
                stringValue(result.get("status")),
                refund.getChannelStatus()));
        refund.setAuditUser(auditUser);
        refund.setAuditTime(LocalDateTime.now());
        refund.setUpdateTime(LocalDateTime.now());

        if (mappedStatus == RefundStatus.SUCCESS) {
            // 累计所有已成功退款金额，判断全退/部分退
            Long totalRefunded = refundMapper.sumSuccessAmountByOrderNo(order.getOrderNo());
            PaymentOrderStatus nextStatus = (totalRefunded != null && totalRefunded.equals(order.getAmount()))
                    ? PaymentOrderStatus.REFUNDED
                    : PaymentOrderStatus.PARTIAL_REFUNDED;
            paymentOrderMapper.updateStatus(order.getOrderNo(), nextStatus.getCode(), null, null);
            
            if (isOrderPlanPayment(order)) {
                updateOrderPlanStatusOnRefundSuccess(order.getPlanNo());
            }
        }
    }

    private void updateOrderPlanStatusOnRefundSuccess(String planNo) {
        if (!StringUtils.hasText(planNo)) {
            return;
        }
        // 发布MQ事件：退款成功，由业务系统自行更新订单计划状态
        try {
            RefundCompletedMessage msg = new RefundCompletedMessage();
            msg.setBusinessOrderNo(planNo);
            msg.setStatus("SUCCESS");
            paymentEventProducer.sendRefundCompleted(msg);
            log.info("已发送退款成功MQ消息 - bizNo: {}", planNo);
        } catch (Exception e) {
            log.error("发送退款成功MQ消息失败 - bizNo: {}, error: {}", planNo, e.getMessage(), e);
        }
    }

    private Map<String, Object> buildRefundSyncResult(Refund refund, PaymentOrder order, String source) {
        Map<String, Object> result = new HashMap<>();
        result.put("refundNo", refund.getRefundNo());
        result.put("orderNo", refund.getOrderNo());
        result.put("platform", order.getPlatform());
        result.put("status", refund.getStatus());
        result.put("channelStatus", refund.getChannelStatus());
        result.put("thirdPartyRefundNo", refund.getThirdPartyRefundNo());
        result.put("source", source);
        return result;
    }

    private RefundStatus mapRefundStatus(String platform, Map<String, Object> result) {
        if (isWechatRefundSuccess(result)) {
            return RefundStatus.SUCCESS;
        }
        String normalized = normalizeRefundStatus(platform, result);
        if (!StringUtils.hasText(normalized)) {
            return RefundStatus.PROCESSING;
        }
        return switch (normalized) {
            case PaymentConstants.REFUND_STATUS_SUCCESS, PaymentConstants.REFUND_STATUS_REFUND_SUCCESS, PaymentConstants.REFUND_STATUS_FINISHED -> RefundStatus.SUCCESS;
            case PaymentConstants.REFUND_STATUS_FAILED, PaymentConstants.REFUND_STATUS_CLOSED, PaymentConstants.REFUND_STATUS_ABNORMAL, PaymentConstants.REFUND_STATUS_REJECTED, PaymentConstants.REFUND_STATUS_FAIL, PaymentConstants.REFUND_STATUS_ERROR -> RefundStatus.FAILED;
            default -> RefundStatus.PROCESSING;
        };
    }

    private String normalizeRefundStatus(String platform, Map<String, Object> result) {
        if (PaymentConstants.PLATFORM_ALIPAY.equalsIgnoreCase(platform)) {
            String refundStatus = stringValue(result.get("status"));
            if (StringUtils.hasText(refundStatus)) {
                return refundStatus.trim().toUpperCase();
            }
            String fundChange = stringValue(result.get("fundChange"));
            if ("Y".equalsIgnoreCase(fundChange)) {
                return PaymentConstants.REFUND_STATUS_REFUND_SUCCESS;
            }
            String errorCode = stringValue(result.get("errorCode"));
            if (StringUtils.hasText(errorCode)) {
                return PaymentConstants.REFUND_STATUS_FAILED;
            }
            return null;
        }

        String channelStatus = firstNonBlank(
                stringValue(result.get("channelStatus")),
                stringValue(result.get("status")));
        if (!StringUtils.hasText(channelStatus)) {
            return null;
        }
        String normalized = channelStatus.trim().toUpperCase();
        if (normalized.contains(PaymentConstants.REFUND_STATUS_SUCCESS)) {
            return PaymentConstants.REFUND_STATUS_SUCCESS;
        }
        if (normalized.contains(PaymentConstants.REFUND_STATUS_ABNORMAL)) {
            return PaymentConstants.REFUND_STATUS_ABNORMAL;
        }
        if (normalized.contains(PaymentConstants.REFUND_STATUS_CLOSED)) {
            return PaymentConstants.REFUND_STATUS_CLOSED;
        }
        if (normalized.contains("FAIL")) {
            return PaymentConstants.REFUND_STATUS_FAILED;
        }
        if (normalized.contains("REJECT")) {
            return PaymentConstants.REFUND_STATUS_REJECTED;
        }
        if (normalized.contains("PROCESS") || normalized.contains("AUDIT") || normalized.contains("PENDING")) {
            return PaymentConstants.REFUND_STATUS_PROCESSING;
        }
        return normalized;
    }

    private boolean isWechatRefundSuccess(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        return StringUtils.hasText(stringValue(result.get("successTime")))
                || StringUtils.hasText(stringValue(result.get("success_time")));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void scheduleRefundStatusAutoSync(String refundNo, int attempt) {
        if (!StringUtils.hasText(refundNo) || attempt > PaymentRetryConstants.MAX_RETRY_ATTEMPTS) {
            return;
        }
        long delaySeconds = PaymentRetryConstants.getDelaySeconds(attempt);
        paymentAsyncExecutor.schedule(() -> {
            try {
                Map<String, Object> result = syncRefundStatus(refundNo);
                String status = stringValue(result.get("status"));
                if (RefundStatus.PROCESSING.getCode().equals(status)) {
                    scheduleRefundStatusAutoSync(refundNo, attempt + 1);
                }
            } catch (Exception e) {
                log.warn("自动同步退款状态失败 - refundNo: {}, attempt: {}, error: {}",
                        refundNo, attempt, e.getMessage());
                scheduleRefundStatusAutoSync(refundNo, attempt + 1);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void safeMarkPaymentEventFailed(PaymentEvent event, Exception e) {
        if (event == null || event.getId() == null) {
            return;
        }
        try {
            event.setStatusEnum(PaymentEventStatus.FAILED);
            String message = e == null ? "callback failed" : e.getMessage();
            if (message != null && message.length() > 500) {
                message = message.substring(0, 500);
            }
            event.setResponseContent(message);
            paymentEventMapper.update(event);
        } catch (Exception updateError) {
            log.error("更新支付事件失败状态失败 - eventId: {}, error: {}", event.getId(), updateError.getMessage(), updateError);
        }
    }

    // ==================== 内部查询（供 drone-system Feign 调用） ====================

    @Override
    public PaymentOrder getLatestPaymentOrderByPlanNo(String planNo) {
        return paymentOrderMapper.selectLatestByPlanNo(planNo);
    }

    @Override
    public java.util.List<Refund> getRefundsByOrderNo(String orderNo) {
        return refundMapper.selectByOrderNo(orderNo);
    }

    @Override
    public Refund getLatestPendingRefundByOrderNo(String orderNo) {
        return refundMapper.selectLatestPendingByOrderNo(orderNo);
    }
}
