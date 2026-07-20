package club.beenest.payment.paymentorder.service.impl;

import club.beenest.payment.common.annotation.LogAudit;
import club.beenest.payment.common.constant.PaymentRedisKeyConstants;
import club.beenest.payment.common.utils.AuthUtils;
import club.beenest.payment.shared.scheduler.OutboxMessageScheduler;
import org.springframework.data.redis.core.StringRedisTemplate;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.constant.PaymentConstants;
import club.beenest.payment.shared.constant.PaymentRetryConstants;
import club.beenest.payment.paymentorder.domain.enums.PaymentEventStatus;
import club.beenest.payment.paymentorder.domain.enums.PaymentEventType;
import club.beenest.payment.paymentorder.domain.enums.PaymentOrderStatus;
import club.beenest.payment.paymentorder.domain.enums.RefundStatus;
import club.beenest.payment.wallet.domain.enums.WalletTransactionType;
import club.beenest.payment.util.MapValueUtils;
import club.beenest.payment.util.PaymentValidateUtils;
import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.RefundCompletedMessage;
import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.paymentorder.mapper.PaymentEventMapper;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.paymentorder.mapper.RefundMapper;

import club.beenest.payment.paymentorder.dto.BatchSyncResultDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentRequestDTO;
import club.beenest.payment.paymentorder.dto.OrderPaymentResultDTO;
import club.beenest.payment.paymentorder.dto.PaymentOrderQueryDTO;
import club.beenest.payment.paymentorder.dto.PaymentStatusDTO;
import club.beenest.payment.paymentorder.dto.RechargeRequestDTO;
import club.beenest.payment.paymentorder.dto.RefundQueryDTO;
import club.beenest.payment.paymentorder.dto.RefundSyncResultDTO;
import club.beenest.payment.paymentorder.domain.entity.PaymentEvent;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import club.beenest.payment.paymentorder.domain.enums.OrderRefundPolicy;
import club.beenest.payment.wallet.domain.entity.Wallet;
import club.beenest.payment.paymentorder.service.IPaymentService;
import club.beenest.payment.wallet.service.IWalletService;
import club.beenest.payment.paymentorder.strategy.PaymentStrategy;
import club.beenest.payment.paymentorder.strategy.PaymentStrategyFactory;
import club.beenest.payment.common.utils.MoneyUtil;
import club.beenest.payment.common.utils.TradeNoGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.page.PageMethod;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
     * 自注入代理引用，用于调用本类的 @Transactional 方法。
     * Spring AOP 代理模式下，同一 Bean 内 this.xxx() 调用绕过代理，
     * 导致 @Transactional 注解不生效。通过 @Lazy 自注入获取代理对象解决此问题。
     */
    @Autowired
    @Lazy
    private PaymentServiceImpl self;

    /** 回调/查询结果中平台状态的 Map 键 */
    private static final String KEY_PLATFORM_STATUS = "platformStatus";
    /** 回调/查询结果中金额的 Map 键 */
    private static final String KEY_AMOUNT = "amount";
    /** 回调/查询结果中状态的 Map 键 */
    private static final String KEY_STATUS = "status";

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
    public OrderPaymentResultDTO createRechargeOrder(String customerNo, RechargeRequestDTO rechargeRequest) {
        log.info("创建充值订单 - customerNo: {}, amount: {}, platform: {}",
                customerNo, rechargeRequest.getAmount(), rechargeRequest.getPlatform());

        try {
            // 1. 验证参数
            validateRechargeRequest(customerNo, rechargeRequest);
            String paymentMethod = rechargeRequest.getDefaultPaymentMethod();
            String openid = resolveWechatOpenid(customerNo, rechargeRequest.getOpenid(),
                    rechargeRequest.getPlatform(), paymentMethod);

            // 2. 查询用户钱包（多租户：充值订单默认使用 DRONE_ORDER 钱包）
            String walletBizType = rechargeRequest.getBizType() != null ? rechargeRequest.getBizType() : BizTypeConstants.DRONE_ORDER;
            Wallet wallet = walletService.getWallet(customerNo, walletBizType);
            if (wallet == null) {
                log.error("用户钱包不存在 - customerNo: {}, bizType: {}", customerNo, walletBizType);
                throw new IllegalArgumentException("用户钱包不存在");
            }

            // 3. 生成订单号
            String orderNo = generateUniqueOrderNo();

            // 4. 创建订单记录
            PaymentOrder paymentOrder = buildPaymentOrder(orderNo, customerNo, wallet.getWalletNo(), rechargeRequest, openid);
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
            savePaymentParamsSafely(paymentOrder, paymentParams);

            // 8. 构建返回结果
            OrderPaymentResultDTO result = new OrderPaymentResultDTO()
                    .setOrderNo(orderNo)
                    .setAmount(rechargeRequest.getAmount())
                    .setPlatform(rechargeRequest.getPlatform())
                    .setPlatformName(paymentStrategy.getPlatformName())
                    .setExpireTime(paymentOrder.getExpireTime())
                    .setPaymentParams(paymentParams);

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
     * <p>使用策略模式，根据支付平台选择对应的支付策略处理回调。</p>
     *
     * <p><b>安全设计</b>：验签和网络调用在事务外执行，避免长时间持有数据库连接和行锁。
     * 只有数据库操作在事务内执行，保证"更新订单状态 + 入钱包/写Outbox"的原子性。</p>
     *
     * <h4>流程：</h4>
     * <ol>
     * <li>解析回调数据（事务外）</li>
     * <li>获取支付策略、验证回调签名（事务外，含网络调用）</li>
     * <li>解析回调数据（事务外）</li>
     * <li>事务内：查询订单（加行锁）→ 幂等性检查 → 校验金额 → 更新订单状态 → 入钱包/写Outbox</li>
     * </ol>
     */
    @Override
    @LogAudit(module = "支付管理", operation = "处理支付回调")
    public boolean handlePaymentCallback(String platform, HttpServletRequest request) {
        log.info("收到支付回调 - platform: {}", platform);

        PaymentEvent event = new PaymentEvent();
        try {
            // ====== 事务外：验签 + 解析（可能包含网络调用，不持有数据库连接） ======
            Map<String, String> callbackData = parseCallbackData(request);
            log.debug("回调原始数据 - platform: {}, data: {}", platform, callbackData);
            log.info("收到支付回调 - platform: {}", platform);

            // 记录支付事件（事务外写入，不影响后续事务回滚）
            event.setEventNo(TradeNoGenerator.generateTransactionNo());
            event.setEventTypeEnum(PaymentEventType.CALLBACK);
            event.setChannel(platform);
            event.setStatusEnum(PaymentEventStatus.PENDING);
            event.setRequestContent(objectMapper.writeValueAsString(callbackData));
            paymentEventMapper.insert(event);

            // 获取支付策略并验证回调签名（可能包含网络调用，如微信SDK验签+解密）
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(platform);
            if (!paymentStrategy.verifyCallback(callbackData)) {
                log.error("回调签名验证失败 - platform: {}", platform);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Signature verification failed");
                paymentEventMapper.update(event);
                return false;
            }

            // 解析回调数据
            Map<String, Object> parsedData = paymentStrategy.parseCallback(callbackData);
            String orderNo = (String) parsedData.get("orderNo");
            String transactionNo = (String) parsedData.get("transactionNo");
            Long paidAmount = (Long) parsedData.get(KEY_AMOUNT);
            String paidStatus = MapValueUtils.stringValue(parsedData.get(KEY_STATUS));

            event.setOrderNo(orderNo);

            if (!StringUtils.hasText(orderNo)) {
                log.error("回调数据中未找到订单号 - platform: {}", platform);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("OrderNo not found in callback data");
                paymentEventMapper.update(event);
                return false;
            }

            // ====== 事务内：数据库操作（保证原子性） ======
            return self.handlePaymentCallbackInTransaction(platform, event, orderNo, transactionNo, paidAmount, paidStatus, callbackData);

        } catch (Exception e) {
            log.error("处理支付回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            safeMarkPaymentEventFailed(event, e);
            throw new BusinessException("处理支付回调失败: " + e.getMessage(), e);
        }
    }

    /**
     * 支付回调的数据库操作（事务内执行）
     *
     * <p><b>安全关键</b>：将验签和网络调用移到事务外后，此方法只包含数据库操作。
     * 保证"更新订单状态 + 入钱包/写Outbox"在同一事务内原子提交。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaymentCallbackInTransaction(String platform, PaymentEvent event,
            String orderNo, String transactionNo, Long paidAmount, String paidStatus,
            Map<String, String> callbackData) {
        try {
            // 查询订单（加行锁防止并发回调重复处理）
            PaymentOrder paymentOrder = paymentOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (paymentOrder == null) {
                log.error("订单不存在 - orderNo: {}", orderNo);
                event.setStatusEnum(PaymentEventStatus.FAILED);
                event.setResponseContent("Order not found");
                paymentEventMapper.update(event);
                return false;
            }

            // 幂等性检查
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

            // 校验金额（金额为空同样拒绝，防止构造缺失 amount 字段的伪造回调）
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

            // 更新订单状态
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

            // 按订单类型做后续处理（充值订单入钱包；计划订单更新计划状态）
            if (isOrderPlanPayment(paymentOrder)) {
                markBizOrderPaymentSuccess(paymentOrder);
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
            log.error("处理支付回调事务内操作失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            safeMarkPaymentEventFailed(event, e);
            // 【安全关键】必须重新抛出异常以触发@Transactional回滚
            // 如果订单状态已更新为PAID但addBalance失败，吞掉异常会导致事务提交，
            // 造成订单已支付但余额未到账的资金不一致问题
            throw new BusinessException("处理支付回调失败: " + e.getMessage(), e);
        }
    }

    @Override
    @LogAudit(module = "支付管理", operation = "处理退款回调")
    public boolean handleRefundCallback(String platform, HttpServletRequest request) {
        log.info("收到退款回调 - platform: {}", platform);

        try {
            // ====== 事务外：验签 + 解析（可能包含网络调用，不持有数据库连接） ======
            Map<String, String> callbackData = parseCallbackData(request);
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(platform);
            if (!paymentStrategy.verifyRefundCallback(callbackData)) {
                // 【安全关键】签名验证失败时返回 false，让 Controller 返回 FAILURE 给第三方
                // 第三方会重试回调，重试是安全的（有幂等检查）。
                // 不应返回 true/SUCCESS，否则伪造的退款回调会被静默忽略。
                log.error("【安全告警】退款回调签名验证失败 - platform: {}", platform);
                return false;
            }

            Map<String, Object> parsedData = paymentStrategy.parseRefundCallback(callbackData);
            String refundNo = MapValueUtils.stringValue(parsedData.get("refundNo"));
            String refundId = MapValueUtils.stringValue(parsedData.get("refundId"));
            if (!StringUtils.hasText(refundNo)) {
                log.warn("退款回调缺少退款单号，尝试按第三方退款单号匹配 - platform: {}, refundId: {}", platform, refundId);
            }

            // ====== 事务内：数据库操作（保证原子性） ======
            return self.handleRefundCallbackInTransaction(refundNo, refundId, parsedData);

        } catch (Exception e) {
            log.error("处理退款回调失败 - platform: {}, error: {}", platform, e.getMessage(), e);
            throw new BusinessException("处理退款回调失败: " + e.getMessage(), e);
        }
    }

    /**
     * 退款回调的数据库操作（事务内执行）
     *
     * <p><b>安全设计</b>：验签和网络调用移到事务外，此方法只包含数据库操作。
     * 保证退款状态更新与订单状态更新、冻结余额处理的原子性。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefundCallbackInTransaction(String refundNo, String refundId,
            Map<String, Object> parsedData) {
        // 加行锁防止退款回调与主动同步并发冲突
        Refund refund = StringUtils.hasText(refundNo) ? refundMapper.selectByRefundNoForUpdate(refundNo) : null;
        if (refund == null && StringUtils.hasText(refundId)) {
            // 按第三方退款单号查询时也加行锁，防止并发回调和同步冲突
            refund = refundMapper.selectByThirdPartyRefundNoForUpdate(refundId);
        }
        if (refund == null) {
            log.error("退款单不存在 - refundNo: {}, refundId: {}", refundNo, refundId);
            return false;
        }
        if (refund.getStatusEnum() == RefundStatus.SUCCESS || refund.getStatusEnum() == RefundStatus.REJECTED) {
            log.info("退款单已到终态，忽略重复回调 - refundNo: {}, status: {}", refundNo, refund.getStatus());
            return true;
        }

        // 加行锁防止退款回调与主动同步并发冲突
        PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(refund.getOrderNo());
        if (order == null) {
            log.error("退款原支付订单不存在 - refundNo: {}, orderNo: {}", refundNo, refund.getOrderNo());
            return false;
        }

        applyRefundSyncResult(order, refund, parsedData, PaymentConstants.CALLBACK_SOURCE);

        // 充值退款：根据退款结果处理冻结余额
        if (!isOrderPlanPayment(order)) {
            if (refund.getStatusEnum() == RefundStatus.SUCCESS) {
                // 第三方退款成功 → 扣减冻结余额
                boolean deducted = walletService.deductFrozenBalance(
                        order.getCustomerNo(), order.getBizType(), refund.getAmount(),
                        false, false);
                if (!deducted) {
                    log.error("退款回调：退款成功但扣减冻结余额失败 - refundNo: {}, 可能需要人工介入", refund.getRefundNo());
                }
            } else if (refund.getStatusEnum() == RefundStatus.FAILED) {
                // 第三方退款失败 → 解冻余额
                boolean unfreezed = walletService.unfreezeBalance(
                        order.getCustomerNo(), order.getBizType(), refund.getAmount(),
                        "退款失败解冻 - 退款单号：" + refund.getRefundNo(),
                        refund.getRefundNo());
                if (!unfreezed) {
                    log.error("退款回调：退款失败但解冻余额失败 - refundNo: {}, 可能需要人工介入", refund.getRefundNo());
                }
            }
            // PROCESSING 状态：保持冻结，等待下次回调或 Scheduler 补偿
        }

        refund.setAuditRemark(MapValueUtils.firstNonBlank(refund.getAuditRemark(), "渠道退款回调更新"));
        refundMapper.update(refund);
        return true;
    }

    /**
     * 查询订单支付状态
     *
     * <p>
     * 不使用 @Transactional，避免在事务内执行第三方网络调用导致长时间持有数据库连接。
     * 补偿查询结果通过独立的同步方法（带事务）写入。
     * </p>
     */
    @Override
    public PaymentStatusDTO queryPaymentStatus(String customerNo, String orderNo) {
        log.info("查询订单支付状态 - orderNo: {}", orderNo);

        try {
            // 1. 参数验证
            if (!StringUtils.hasText(orderNo)) {
                throw new IllegalArgumentException("订单号不能为空");
            }

            // 2. 查询本地订单（无事务，普通读）
            PaymentOrder paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
            if (paymentOrder == null) {
                throw new IllegalArgumentException("订单不存在");
            }
            if (StringUtils.hasText(customerNo) && !customerNo.equals(paymentOrder.getCustomerNo())) {
                throw new IllegalArgumentException("无权查看该订单");
            }

            // 3. 构建返回结果
            PaymentStatusDTO result = new PaymentStatusDTO()
                    .setOrderNo(orderNo)
                    .setStatus(paymentOrder.getStatus())
                    .setAmount(paymentOrder.getAmount())
                    .setPlatform(paymentOrder.getPlatform())
                    .setCreateTime(paymentOrder.getCreateTime())
                    .setPaidTime(paymentOrder.getPaidTime())
                    .setExpireTime(paymentOrder.getExpireTime())
                    .setBizNo(paymentOrder.getBizNo());

            // 4. 对待支付订单主动向支付渠道补偿查询，兜住回调丢失场景
            //    第三方网络调用在事务外执行，避免长时间持有数据库连接
            if (paymentOrder.isPending()) {
                queryPlatformStatusSafely(paymentOrder);
                paymentOrder = paymentOrderMapper.selectByOrderNo(orderNo);
                result.setStatus(paymentOrder.getStatus());
                result.setPaidTime(paymentOrder.getPaidTime());
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

    /**
     * 安全查询支付平台状态，失败仅记录日志不影响主流程
     *
     * @param paymentOrder 待查询的支付订单
     */
    private void queryPlatformStatusSafely(PaymentOrder paymentOrder) {
        try {
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(paymentOrder.getPlatform());
            Map<String, Object> platformStatus = paymentStrategy.queryPayment(paymentOrder);
            if (platformStatus != null) {
                syncPaymentOrderFromQuerySafe(paymentOrder, platformStatus);
            }
        } catch (Exception e) {
            log.warn("查询支付平台状态失败 - orderNo: {}, error: {}", paymentOrder.getOrderNo(), e.getMessage());
        }
    }

    /**
     * 安全调用支付平台取消订单，失败仅记录日志不影响本地状态更新
     *
     * @param paymentOrder 待取消的支付订单
     */
    private void cancelPaymentOnPlatformSafely(PaymentOrder paymentOrder) {
        try {
            PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(paymentOrder.getPlatform());
            paymentStrategy.cancelPayment(paymentOrder);
        } catch (Exception e) {
            log.warn("调用支付平台取消订单失败 - orderNo: {}, error: {}", paymentOrder.getOrderNo(), e.getMessage());
        }
    }

    @Override
    public PaymentStatusDTO queryPaymentStatusForAdmin(String orderNo) {
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

            // 5. 调用支付平台取消订单（失败仅记录日志，不影响本地状态更新）
            cancelPaymentOnPlatformSafely(paymentOrder);

            // 6. 更新订单状态
            int updateResult = paymentOrderMapper.updateStatus(orderNo, PaymentOrderStatus.CANCELLED.getCode(), null,
                    null);
            if (updateResult != 1) {
                log.error("取消订单失败 - orderNo: {}", orderNo);
                throw new BusinessException("取消订单失败");
            }

            if (isOrderPlanPayment(paymentOrder)) {
                rollbackBizOrderPaymentState(paymentOrder);
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
    public OrderPaymentResultDTO createOrderPayment(String customerNo, OrderPaymentRequestDTO request) {
        log.info("创建订单支付 - customerNo: {}, bizNo: {}, amount: {}, payType: {}, paymentMethod: {}",
                customerNo, request.getBizNo(), request.getAmount(), request.getPayType(), request.getDefaultPaymentMethod());

        try {
            validateOrderPaymentRequest(customerNo, request);

            String backendPlatform = request.getBackendPlatform();
            String paymentMethod = request.getDefaultPaymentMethod();
            String bizNo = request.getBizNo();
            String openid = resolveWechatOpenid(customerNo, request.getOpenid(), backendPlatform, paymentMethod);

            // 金额计算：调用方传入amount（最终支付金额），originalAmount/discountAmount用于展示
            Long finalAmount = request.getAmount();
            Long baseAmount = request.getOriginalAmount() != null ? request.getOriginalAmount() : finalAmount;
            Long discountAmount = request.getDiscountAmount() != null ? request.getDiscountAmount() : 0L;

            // 如果有优惠券，计算折扣
            CouponDiscountResult discountResult = calculateCouponDiscount(request);
            if (discountResult.discountAmount() > 0) {
                discountAmount = discountResult.discountAmount();
                finalAmount = Math.max(baseAmount - discountAmount, 0L);
            }

            validatePaymentAmount(finalAmount, request.getAmount());

            // 检查是否有待支付订单（加行锁防止并发创建多笔支付）
            PaymentOrder latestPending = paymentOrderMapper.selectLatestPendingByBizNoForUpdate(bizNo);
            if (latestPending != null && latestPending.canPay()) {
                return handleExistingPendingOrder(latestPending, backendPlatform, paymentMethod,
                        finalAmount, baseAmount, discountAmount, bizNo);
            }

            return createNewOrderPayment(new NewOrderParams(customerNo, bizNo, request.getBizType(), backendPlatform, paymentMethod,
                    finalAmount, baseAmount, discountAmount, discountResult.userCouponNo(), openid));

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
        PaymentValidateUtils.notBlank(request.getBizNo(), "业务单号不能为空");
        String backendPlatform = request.getBackendPlatform();
        PaymentValidateUtils.isTrue(paymentStrategyFactory.isEnabled(backendPlatform),
                () -> "支付平台未启用: " + request.getPayType());

        String paymentMethod = request.getDefaultPaymentMethod();
        PaymentValidateUtils.notBlank(paymentMethod, "支付渠道不能为空");
        if (PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(backendPlatform)) {
            PaymentValidateUtils.isTrue(
                    PaymentConstants.METHOD_WECHAT_APP.equalsIgnoreCase(paymentMethod)
                            || PaymentConstants.METHOD_WECHAT_JSAPI.equalsIgnoreCase(paymentMethod),
                    "微信支付渠道不合法");
        }
    }

    private CouponDiscountResult calculateCouponDiscount(OrderPaymentRequestDTO request) {
        // 优惠券逻辑已迁出支付中台，由调用方直接传入优惠金额
        Long discountAmount = request.getDiscountAmount() != null ? request.getDiscountAmount() : 0L;
        return new CouponDiscountResult(discountAmount, null);
    }

    private void validatePaymentAmount(Long finalAmount, Long requestAmount) {
        PaymentValidateUtils.isTrue(finalAmount != null && finalAmount > 0, "支付金额必须大于0");
        PaymentValidateUtils.isTrue(requestAmount == null || requestAmount.equals(finalAmount),
                "支付金额校验失败，请刷新后重试");
    }

    /**
     * 解析微信 JSAPI 支付所需的 openid。
     *
     * <p>仅当支付渠道为微信 JSAPI 时才需要 openid。
     * 当前实现只信任 TGT 里携带的认证属性，不再回查 CAS 用户资料。
     * 这样可以保证支付链路完全依赖单一认证源，避免 payment 侧引入额外同步实现。</p>
     *
     * @param customerNo 用户编号
     * @param backendPlatform 后端支付平台
     * @param paymentMethod 支付渠道方式
     * @return 可信的微信 openid；非微信 JSAPI 支付返回 null
     */
    private String resolveWechatOpenid(String customerNo, String requestOpenid, String backendPlatform, String paymentMethod) {
        if (!PaymentConstants.PLATFORM_WECHAT.equalsIgnoreCase(backendPlatform)
                || !PaymentConstants.requiresWechatOpenid(paymentMethod)) {
            return null;
        }

        if (StringUtils.hasText(requestOpenid)) {
            return requestOpenid;
        }

        String currentUserId = AuthUtils.getCurrentUserId();
        String currentOpenid = AuthUtils.getCurrentAttribute(
                "openid",
                "wechatOpenid",
                "wechat_openid",
                "wxOpenid",
                "wx_openid",
                "mp_weixin_openid",
                "miniapp_wechat_openid");

        if (!StringUtils.hasText(currentUserId)) {
            throw new IllegalArgumentException("微信 JSAPI 支付需要已登录用户");
        }
        PaymentValidateUtils.isTrue(customerNo.equals(currentUserId),
                "登录用户与支付用户不一致，请重新登录后重试");

        if (StringUtils.hasText(currentOpenid)) {
            return currentOpenid;
        }

        throw new IllegalArgumentException("微信 JSAPI 支付缺少 openid，请重新微信授权登录");
    }

    private OrderPaymentResultDTO handleExistingPendingOrder(PaymentOrder existingOrder, String backendPlatform,
            String paymentMethod, Long finalAmount, Long baseAmount, Long discountAmount, String bizNo) {
        String existingPlatform = existingOrder.getPlatform();

        if (!backendPlatform.equalsIgnoreCase(existingPlatform)) {
            throw new IllegalArgumentException("存在待支付订单（支付方式不一致），请继续支付或先取消后重试");
        }
        if (StringUtils.hasText(paymentMethod) && !paymentMethod.equalsIgnoreCase(existingOrder.getPaymentMethod())) {
            throw new IllegalArgumentException("存在待支付订单（支付渠道不一致），请继续支付或先取消后重试");
        }
        if (!finalAmount.equals(existingOrder.getAmount())) {
            throw new IllegalArgumentException("存在待支付订单（金额不一致），请继续支付或先取消后重试");
        }

        // 预查第三方支付状态，防止复用已实际支付的订单导致重复支付
        try {
            PaymentStrategy preCheckStrategy = paymentStrategyFactory.getStrategy(existingPlatform);
            Map<String, Object> platformStatus = preCheckStrategy.queryPayment(existingOrder);
            if (platformStatus != null && isPlatformPaid(
                    platformStatus.get(KEY_PLATFORM_STATUS) == null ? null : platformStatus.get(KEY_PLATFORM_STATUS).toString())) {
                // 第三方已支付成功，走补偿同步路径
                log.warn("复用待支付订单时发现第三方已支付，走补偿路径 - orderNo: {}", existingOrder.getOrderNo());
                syncPaymentOrderFromQuerySafe(existingOrder, platformStatus);
                // 返回已支付状态（前端应根据状态展示支付结果）
                throw new BusinessException("该订单已支付成功，请刷新页面查看");
            }
        } catch (BusinessException e) {
            throw e; // 重新抛出业务异常
        } catch (Exception e) {
            // 查询失败降级：不阻塞用户继续支付流程
            log.warn("预查第三方支付状态失败，降级继续复用 - orderNo: {}, error: {}",
                    existingOrder.getOrderNo(), e.getMessage());
        }

        PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(backendPlatform);
        Map<String, Object> paymentParams = getOrCreatePaymentParams(existingOrder, paymentStrategy);

        log.info("复用待支付订单 - orderNo: {}, bizNo: {}", existingOrder.getOrderNo(), bizNo);
        return buildOrderPaymentResult(new OrderPaymentResultParams(existingOrder.getOrderNo(), bizNo, finalAmount, baseAmount, discountAmount,
                backendPlatform, existingOrder.getPaymentMethod(), paymentStrategy.getPlatformName(),
                existingOrder.getExpireTime(), paymentParams));
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

    /** 创建新订单的参数封装 */
    private record NewOrderParams(String customerNo, String bizNo, String bizType,
                                   String backendPlatform, String paymentMethod, Long finalAmount,
                                   Long baseAmount, Long discountAmount, String usedCouponNo, String openid) {}

    private OrderPaymentResultDTO createNewOrderPayment(NewOrderParams p) {
        String customerNo = p.customerNo();
        String bizNo = p.bizNo();
        String bizType = p.bizType();
        String backendPlatform = p.backendPlatform();
        String paymentMethod = p.paymentMethod();
        Long finalAmount = p.finalAmount();
        Long baseAmount = p.baseAmount();
        Long discountAmount = p.discountAmount();
        String usedCouponNo = p.usedCouponNo();
        String openid = p.openid();
        String walletBizType = bizType != null ? bizType : BizTypeConstants.DRONE_ORDER;
        Wallet wallet = walletService.getWallet(customerNo, walletBizType);
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
        paymentOrder.setPaymentMethod(paymentMethod);
        paymentOrder.setStatusEnum(PaymentOrderStatus.PENDING);
        paymentOrder.setExpireTime(expireTime);
        paymentOrder.setNotifyUrl(getNotifyUrl(backendPlatform));
        paymentOrder.setBizNo(bizNo);
        paymentOrder.setBizType(walletBizType);
        paymentOrder.setAppId(BizTypeConstants.deriveAppId(walletBizType));
        paymentOrder.setExt(buildPaymentOrderExt(openid));
        paymentOrder.setRemark(buildBizOrderRemark(bizNo));
        paymentOrder.setCreateTime(now);
        paymentOrder.setUpdateTime(now);

        int insertResult = paymentOrderMapper.insert(paymentOrder);
        if (insertResult != 1) {
            throw new BusinessException("创建支付订单失败");
        }

        String expireKey = PaymentRedisKeyConstants.buildPaymentOrderExpireKey(orderNo);
        stringRedisTemplate.opsForValue().set(expireKey, orderNo, java.time.Duration.ofMinutes(expireMinutes));
        log.info("设置支付订单过期Key - orderNo: {}, expireMinutes: {}", orderNo, expireMinutes);

        PaymentStrategy paymentStrategy = paymentStrategyFactory.getStrategy(backendPlatform);
        Map<String, Object> paymentParams = paymentStrategy.createPayment(paymentOrder);
        savePaymentParamsSafely(paymentOrder, paymentParams);

        log.info("订单支付创建完成 - orderNo: {}, bizNo: {}", orderNo, bizNo);
        return buildOrderPaymentResult(new OrderPaymentResultParams(orderNo, bizNo, finalAmount, baseAmount, discountAmount,
                backendPlatform, paymentOrder.getPaymentMethod(), paymentStrategy.getPlatformName(),
                paymentOrder.getExpireTime(), paymentParams));
    }

    /**
     * 构建支付订单扩展字段。
     *
     * <p>微信 openid 不再塞进 returnUrl，而是放入 ext 的 JSON 中，
     * 避免污染跳转地址语义，并便于后续扩展更多支付相关元数据。</p>
     *
     * @param openid 微信 openid
     * @return ext JSON 字符串
     */
    private String buildPaymentOrderExt(String openid) {
        if (!StringUtils.hasText(openid)) {
            return null;
        }
        ObjectNode ext = objectMapper.createObjectNode();
        ext.put("openid", openid);
        return ext.toString();
    }

    private record CouponDiscountResult(Long discountAmount, String userCouponNo) {}

    /**
     * 判断是否为业务订单支付（需要通过 MQ 通知业务系统）
     *
     * <p>判断逻辑：有 bizNo 且 bizType 为业务订单类型时走 MQ 通知路径；
     * 充值订单（无 bizNo 或 bizType 为空）走钱包入账路径。</p>
     *
     * <p>当前所有 bizType（DRONE_ORDER、SHOP_ORDER 等）均为业务订单类型，
     * 因为充值订单不需要传 bizNo，而业务订单支付必定同时携带 bizNo 和 bizType。</p>
     */
    private boolean isOrderPlanPayment(PaymentOrder paymentOrder) {
        if (paymentOrder == null) {
            return false;
        }
        // 有 bizNo 说明是业务订单支付，需要 MQ 通知业务系统
        return StringUtils.hasText(paymentOrder.getBizNo());
    }

    private String extractBizNoFromPaymentOrder(PaymentOrder paymentOrder) {
        if (paymentOrder == null) {
            return null;
        }
        return paymentOrder.getBizNo();
    }

    private String buildBizOrderRemark(String bizNo) {
        return PaymentConstants.BIZ_ORDER_PREFIX + bizNo;
    }

    /**
     * 标记业务订单支付成功，写入 Outbox 消息。
     *
     * <p><b>安全关键</b>：使用事务内 Outbox 直写代替 afterCommit + MQ 直发。
     * 原方案在事务提交后异步发送 MQ，如果 MQ 不可用则消息丢失，
     * 导致业务订单永远不知道支付已成功。</p>
     *
     * <p>新方案在同一个 {@code @Transactional} 事务内写入 Outbox 表，
     * 由 {@link OutboxMessageScheduler} 补偿发送，保证消息最终一定送达。</p>
     */
    private void markBizOrderPaymentSuccess(PaymentOrder paymentOrder) {
        String bizNo = extractBizNoFromPaymentOrder(paymentOrder);
        if (StringUtils.hasText(bizNo)) {
            PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
            msg.setOrderNo(paymentOrder.getOrderNo());
            msg.setBusinessOrderNo(bizNo);
            msg.setCustomerNo(paymentOrder.getCustomerNo());
            msg.setAmountFen(paymentOrder.getAmount());
            msg.setPlatform(paymentOrder.getPlatform());
            msg.setBizType(paymentOrder.getBizType());
            msg.setAppId(paymentOrder.getAppId() != null ? paymentOrder.getAppId() : BizTypeConstants.deriveAppId(paymentOrder.getBizType()));
            msg.setPaidAt(paymentOrder.getPaidTime() != null ? paymentOrder.getPaidTime().toString() : LocalDateTime.now().toString());

            // 事务内直写 Outbox，与支付状态更新原子提交
            paymentEventProducer.sendOrderCompletedToOutbox(msg);
            log.info("支付成功消息已写入Outbox - orderNo: {}, bizNo: {}", msg.getOrderNo(), bizNo);
        }
    }

    /**
     * 回滚业务订单支付状态，写入 Outbox 取消消息。
     *
     * <p><b>安全关键</b>：同 {@link #markBizOrderPaymentSuccess}，
     * 使用事务内 Outbox 直写代替 afterCommit + MQ 直发，保证消息不丢失。</p>
     */
    private void rollbackBizOrderPaymentState(PaymentOrder paymentOrder) {
        String bizNo = paymentOrder.getBizNo();
        if (!StringUtils.hasText(bizNo)) {
            return;
        }
        PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
        msg.setOrderNo(paymentOrder.getOrderNo());
        msg.setBusinessOrderNo(bizNo);
        msg.setCustomerNo(paymentOrder.getCustomerNo());
        msg.setAmountFen(paymentOrder.getAmount());
        msg.setPlatform(paymentOrder.getPlatform());
        msg.setBizType(paymentOrder.getBizType());
        msg.setAppId(paymentOrder.getAppId() != null ? paymentOrder.getAppId() : BizTypeConstants.deriveAppId(paymentOrder.getBizType()));

        // 事务内直写 Outbox，与订单状态更新原子提交
        paymentEventProducer.sendOrderCancelledToOutbox(msg);
        log.info("订单取消消息已写入Outbox - orderNo: {}, bizNo: {}", msg.getOrderNo(), bizNo);
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
            RechargeRequestDTO rechargeRequest, String openid) {
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
        paymentOrder.setExt(buildPaymentOrderExt(openid));

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
        PageMethod.startPage(pageNum, pageSize);
        return (Page<PaymentOrder>) paymentOrderMapper.selectByQuery(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogAudit(module = "支付管理", operation = "申请退款")
    public Refund applyRefund(String orderNo, Long amount, String reason) {
        log.info("申请退款（聚合贪心模式）- identifier: {}, amount: {}, reason: {}", orderNo, amount, reason);

        // 1. 先尝试直接按 orderNo 精确匹配单笔退款（兼容旧逻辑），加行锁防并发
        PaymentOrder directOrder = paymentOrderMapper.selectByOrderNoForUpdate(orderNo);
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

        // 2. 未找到直接匹配，按 bizNo 聚合所有支付流水（首付 + 补差），贪心分配退款
        List<PaymentOrder> planOrders = paymentOrderMapper.selectSuccessfulByBizNo(orderNo);
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
        PageMethod.startPage(pageNum, pageSize);
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
    public RefundSyncResultDTO syncRefundStatus(String refundNo) {
        if (!StringUtils.hasText(refundNo)) {
            throw new BusinessException("退款单号不能为空");
        }

        Refund refund = refundMapper.selectByRefundNo(refundNo);
        if (refund == null) {
            throw new BusinessException("退款单不存在");
        }

        // 加行锁防止退款同步与回调并发冲突
        PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(refund.getOrderNo());
        if (order == null) {
            throw new BusinessException("原支付订单不存在");
        }

        if (refund.getStatusEnum() == RefundStatus.SUCCESS || refund.getStatusEnum() == RefundStatus.REJECTED) {
            return buildRefundSyncResult(refund, order, PaymentConstants.LOCAL_SOURCE);
        }

        if (shouldResubmitRefundBeforeQuery(order, refund)) {
            executeRefund(order, refund, PaymentConstants.MANUAL_SYNC_SOURCE, MapValueUtils.firstNonBlank(refund.getAuditRemark(), "补偿提交历史待处理退款"));
            return buildRefundSyncResult(refund, order, PaymentConstants.RESUBMIT_SOURCE);
        }

        PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
        Map<String, Object> channelResult;
        try {
            channelResult = strategy.queryRefund(order, refund);
        } catch (Exception e) {
            if (shouldResubmitRefundAfterQueryFailure(order, e)) {
                log.warn("退款查询命中微信历史单，改为按商户退款单号补发 - refundNo: {}, error: {}",
                        refund.getRefundNo(), e.getMessage());
                refund.setThirdPartyRefundNo(null);
                executeRefund(order, refund, PaymentConstants.MANUAL_SYNC_SOURCE, MapValueUtils.firstNonBlank(refund.getAuditRemark(), "微信历史退款查询不存在，补偿重提"));
                return buildRefundSyncResult(refund, order, PaymentConstants.RESUBMIT_SOURCE);
            }
            throw e;
        }
        applyRefundSyncResult(order, refund, channelResult, PaymentConstants.MANUAL_SYNC_SOURCE);
        refundMapper.update(refund);
        return buildRefundSyncResult(refund, order, PaymentConstants.CHANNEL_SOURCE);
    }

    @Override
    public BatchSyncResultDTO syncProcessingRefunds(int limit) {
        int effectiveLimit = Math.max(limit, 1);
        List<Refund> refunds = refundMapper.selectByStatusForSync(RefundStatus.PROCESSING.getCode(), effectiveLimit);

        int successCount = 0;
        int failedCount = 0;
        for (Refund refund : refunds) {
            try {
                RefundSyncResultDTO result = self.syncRefundStatus(refund.getRefundNo());
                if (RefundStatus.SUCCESS.getCode().equals(result.getStatus())) {
                    successCount++;
                }
            } catch (Exception e) {
                failedCount++;
                log.warn("批量同步退款状态失败 - refundNo: {}, error: {}", refund.getRefundNo(), e.getMessage());
            }
        }

        return new BatchSyncResultDTO()
                .setRequested(effectiveLimit)
                .setScanned(refunds.size())
                .setSuccess(successCount)
                .setFailed(failedCount);
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
            // 加行锁防止退款审核与退款回调并发
            PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(refund.getOrderNo());
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

    /**
     * 从第三方查询结果同步支付状态（安全包装，不向上抛异常）
     * 供 queryPaymentStatus（无事务）调用，避免第三方查询异常影响整体查询
     */
    private void syncPaymentOrderFromQuerySafe(PaymentOrder paymentOrder, Map<String, Object> platformStatus) {
        try {
            self.syncPaymentOrderFromQuery(paymentOrder, platformStatus);
        } catch (Exception e) {
            // CAS 冲突或其他并发异常降级为 warn，不影响用户查询结果
            log.warn("补偿同步支付状态失败（降级） - orderNo: {}, error: {}", paymentOrder.getOrderNo(), e.getMessage());
        }
    }

    /**
     * 从第三方查询结果同步支付状态
     *
     * <p><b>安全关键</b>：使用 @Transactional 保证"更新订单状态 + 入钱包/写Outbox"在同一事务内原子提交。
     * 防止订单状态更新成功但入钱包失败导致资金不一致。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncPaymentOrderFromQuery(PaymentOrder paymentOrder, Map<String, Object> platformStatus) throws Exception {
        if (paymentOrder == null || platformStatus == null || !paymentOrder.isPending()) {
            return;
        }

        Object channelStatus = platformStatus.get(KEY_PLATFORM_STATUS);
        if (!isPlatformPaid(channelStatus == null ? null : channelStatus.toString())) {
            return;
        }

        String callbackDataJson = objectMapper.writeValueAsString(platformStatus);
        String transactionNo = MapValueUtils.stringValue(platformStatus.get("transactionId"));
        if (!StringUtils.hasText(transactionNo)) {
            transactionNo = MapValueUtils.stringValue(platformStatus.get("transactionNo"));
        }

        // 金额校验：paidAmount 为 null 或不匹配时拒绝更新，与回调处理保持一致
        Long paidAmount = MapValueUtils.longValue(platformStatus.get(KEY_AMOUNT));
        if (paidAmount == null || !paidAmount.equals(paymentOrder.getAmount())) {
            log.error("补偿查询金额校验失败 - orderNo: {}, expected: {}, actual: {}",
                    paymentOrder.getOrderNo(), paymentOrder.getAmount(), paidAmount);
            return;
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
            markBizOrderPaymentSuccess(paymentOrder);
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

    private boolean shouldResubmitRefundAfterQueryFailure(PaymentOrder order, Exception e) {
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
        // 使用 FOR UPDATE 行锁防止并发退款超额
        PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(orderNo);
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

    /**
     * 执行退款（安全重构版）
     *
     * <p><b>安全设计</b>：</p>
     * <ul>
     *   <li>充值退款：冻结余额 → 调第三方退款 → 成功扣减冻结 / 失败解冻 / PROCESSING 保持冻结</li>
     *   <li>业务订单退款：直接调第三方退款 → 更新状态 → 写 Outbox</li>
     *   <li>第三方网络调用在事务外执行，避免长时间持有数据库连接和行锁</li>
     * </ul>
     *
     * <p><b>down机恢复</b>：</p>
     * <ul>
     *   <li>冻结后down机 → 余额冻结不可用，RefundStatusSyncScheduler 扫描 PENDING/PROCESSING 补偿</li>
     *   <li>第三方退款成功后down机 → 同上，Scheduler 查询第三方结果后扣减冻结或解冻</li>
     * </ul>
     */
    private void executeRefund(PaymentOrder order, Refund refund, String auditUser, String auditRemark) {
        if (refund.getStatusEnum() == RefundStatus.SUCCESS) {
            return;
        }
        if (refund.getStatusEnum() == RefundStatus.PROCESSING
                && StringUtils.hasText(refund.getThirdPartyRefundNo())) {
            return;
        }

        // 充值退款：先冻结余额，再调第三方退款
        // 冻结成功后即使down机，余额也不会丢失（不可用但未扣除），Scheduler会补偿
        if (!isOrderPlanPayment(order)) {
            BigDecimal refundAmountYuan = MoneyUtil.centsToYuan(refund.getAmount());
            boolean freezeSuccess = walletService.freezeBalance(
                    order.getCustomerNo(), order.getBizType(), refund.getAmount(),
                    "充值退款冻结 - 退款单号：" + refund.getRefundNo(),
                    refund.getRefundNo());
            if (!freezeSuccess) {
                throw new BusinessException("退款失败：用户余额不足或冻结失败");
            }
        }

        // 调第三方退款接口（网络调用，在当前事务外更安全，但此处保持事务内
        // 以确保 Refund 状态更新与第三方调用的原子性）
        PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
        Map<String, Object> result = strategy.refund(order, refund.getAmount(), refund.getReason(), refund.getRefundNo());
        applyRefundSyncResult(order, refund, result, auditUser);

        // 根据退款结果处理冻结余额
        if (!isOrderPlanPayment(order)) {
            if (refund.getStatusEnum() == RefundStatus.SUCCESS) {
                // 第三方退款成功 → 扣减冻结余额
                boolean deducted = walletService.deductFrozenBalance(
                        order.getCustomerNo(), order.getBizType(), refund.getAmount(),
                        false, false);
                if (!deducted) {
                    log.error("退款成功但扣减冻结余额失败 - refundNo: {}, 可能需要人工介入", refund.getRefundNo());
                }
            } else if (refund.getStatusEnum() == RefundStatus.FAILED) {
                // 第三方退款失败 → 解冻余额
                boolean unfreezed = walletService.unfreezeBalance(
                        order.getCustomerNo(), order.getBizType(), refund.getAmount(),
                        "退款失败解冻 - 退款单号：" + refund.getRefundNo(),
                        refund.getRefundNo());
                if (!unfreezed) {
                    log.error("退款失败但解冻余额失败 - refundNo: {}, 可能需要人工介入", refund.getRefundNo());
                }
            }
            // PROCESSING 状态：保持冻结，等待回调或 Scheduler 补偿
        }

        refund.setAuditRemark(auditRemark);
        refund.setUpdateTime(LocalDateTime.now());
        refundMapper.update(refund);

        // [P2 #11] 已移除内存 ScheduledExecutorService，退款状态补偿完全依赖 RefundStatusSyncScheduler
    }

    private void applyRefundSyncResult(PaymentOrder order, Refund refund, Map<String, Object> result, String auditUser) {
        RefundStatus mappedStatus = mapRefundStatus(order.getPlatform(), result);
        String thirdPartyRefundNo = MapValueUtils.firstNonBlank(
                MapValueUtils.stringValue(result.get("refundId")),
                refund.getThirdPartyRefundNo());
        String channelStatus = MapValueUtils.firstNonBlank(
                MapValueUtils.stringValue(result.get("channelStatus")),
                MapValueUtils.stringValue(result.get(KEY_STATUS)),
                refund.getChannelStatus());

        // 使用领域方法执行状态转换（内含合法性校验）
        if (mappedStatus == RefundStatus.SUCCESS) {
            refund.markAsSuccess(thirdPartyRefundNo, channelStatus);
        } else if (mappedStatus == RefundStatus.FAILED) {
            refund.markAsFailed("第三方退款失败");
        } else if (mappedStatus == RefundStatus.PROCESSING) {
            refund.markAsProcessing(thirdPartyRefundNo);
        } else {
            refund.setStatusEnum(mappedStatus);
        }

        refund.setChannelStatus(channelStatus);
        refund.setAuditUser(auditUser);
        refund.setAuditTime(LocalDateTime.now());

        if (mappedStatus == RefundStatus.SUCCESS) {
            // 累计所有已成功退款金额，判断全退/部分退
            Long totalRefunded = refundMapper.sumSuccessAmountByOrderNo(order.getOrderNo());
            if (totalRefunded != null && totalRefunded.equals(order.getAmount())) {
                order.markAsRefunded();
            } else {
                order.markAsPartialRefunded();
            }
            paymentOrderMapper.updateStatus(order.getOrderNo(), order.getStatus(), null, null);

            if (isOrderPlanPayment(order)) {
                updateBizOrderOnRefundSuccess(order, refund);
            }
        }
    }

    private void updateBizOrderOnRefundSuccess(PaymentOrder order, Refund refund) {
        String bizNo = order.getBizNo();
        if (!StringUtils.hasText(bizNo)) {
            return;
        }
        // 写入 Outbox：退款成功，由业务系统自行更新订单计划状态
        try {
            RefundCompletedMessage msg = new RefundCompletedMessage();
            msg.setBusinessOrderNo(bizNo);
            msg.setStatus("SUCCESS");
            msg.setRefundNo(refund.getRefundNo());
            msg.setOrderNo(order.getOrderNo());
            msg.setRefundAmountFen(refund.getAmount());
            msg.setBizType(order.getBizType());
            // 事务内直写 Outbox，保证消息不丢失
            paymentEventProducer.sendRefundCompletedToOutbox(msg);
            log.info("退款成功消息已写入Outbox - bizNo: {}, refundNo: {}", bizNo, refund.getRefundNo());
        } catch (Exception e) {
            log.error("写入退款成功Outbox消息失败 - bizNo: {}, error: {}", bizNo, e.getMessage(), e);
        }
    }

    private RefundSyncResultDTO buildRefundSyncResult(Refund refund, PaymentOrder order, String source) {
        return new RefundSyncResultDTO()
                .setRefundNo(refund.getRefundNo())
                .setOrderNo(refund.getOrderNo())
                .setPlatform(order.getPlatform())
                .setStatus(refund.getStatus())
                .setChannelStatus(refund.getChannelStatus())
                .setThirdPartyRefundNo(refund.getThirdPartyRefundNo())
                .setSource(source);
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
            String refundStatus = MapValueUtils.stringValue(result.get(KEY_STATUS));
            if (StringUtils.hasText(refundStatus)) {
                return refundStatus.trim().toUpperCase();
            }
            String fundChange = MapValueUtils.stringValue(result.get("fundChange"));
            if ("Y".equalsIgnoreCase(fundChange)) {
                return PaymentConstants.REFUND_STATUS_REFUND_SUCCESS;
            }
            String errorCode = MapValueUtils.stringValue(result.get("errorCode"));
            if (StringUtils.hasText(errorCode)) {
                return PaymentConstants.REFUND_STATUS_FAILED;
            }
            return null;
        }

        String channelStatus = MapValueUtils.firstNonBlank(
                MapValueUtils.stringValue(result.get("channelStatus")),
                MapValueUtils.stringValue(result.get(KEY_STATUS)));
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
        return StringUtils.hasText(MapValueUtils.stringValue(result.get("successTime")))
                || StringUtils.hasText(MapValueUtils.stringValue(result.get("success_time")));
    }

    /**
     * 保存支付参数到订单（安全写入，失败仅 warn 不影响主流程）
     */
    private void savePaymentParamsSafely(PaymentOrder order, Map<String, Object> paymentParams) {
        try {
            String paymentParamsJson = objectMapper.writeValueAsString(paymentParams);
            order.setPaymentParams(paymentParamsJson);
            paymentOrderMapper.updateByOrderNo(order);
        } catch (Exception e) {
            log.warn("保存支付参数失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage());
        }
    }

    /**
     * 构建订单支付结果 DTO（统一 createRechargeOrder / handleExistingPendingOrder / createNewOrderPayment 的返回结构）
     */
    /** 订单支付结果构建参数 */
    private record OrderPaymentResultParams(String orderNo, String bizNo, Long amount,
                                             Long originalAmount, Long discountAmount, String platform,
                                             String paymentMethod, String platformName, LocalDateTime expireTime,
                                             Map<String, Object> paymentParams) {}

    private OrderPaymentResultDTO buildOrderPaymentResult(OrderPaymentResultParams p) {
        return new OrderPaymentResultDTO()
                .setOrderNo(p.orderNo())
                .setBizNo(p.bizNo())
                .setAmount(p.amount())
                .setOriginalAmount(p.originalAmount())
                .setDiscountAmount(p.discountAmount())
                .setPlatform(p.platform())
                .setPaymentMethod(p.paymentMethod())
                .setPlatformName(p.platformName())
                .setExpireTime(p.expireTime())
                .setPaymentParams(p.paymentParams());
    }

    // [P2 #11] 已移除 scheduleRefundStatusAutoSync()，退款状态补偿完全依赖
    // RefundStatusSyncScheduler 的 Spring 定时扫描

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
    public PaymentOrder getLatestPaymentOrderByBizNo(String bizNo) {
        return paymentOrderMapper.selectLatestByBizNo(bizNo);
    }

    @Override
    public java.util.List<Refund> getRefundsByOrderNo(String orderNo) {
        return refundMapper.selectByOrderNo(orderNo);
    }

    @Override
    public Refund getLatestPendingRefundByOrderNo(String orderNo) {
        return refundMapper.selectLatestPendingByOrderNo(orderNo);
    }

    @Override
    public List<PaymentOrder> getPaidOrdersByTimeRange(LocalDateTime start, LocalDateTime end, String platform) {
        List<PaymentOrder> orders = paymentOrderMapper.selectByTimeRangeAndPlatform(start, end, platform);
        return orders.stream()
                .filter(o -> "PAID".equals(o.getStatus()))
                .collect(Collectors.toList());
    }

    // ==================== 数据修复（紧急运维） ====================

    /**
     * 修复 EXPIRED 状态但实际已支付的订单
     *
     * <p>修复步骤：</p>
     * <ol>
     *   <li>查询订单，确认状态为 EXPIRED</li>
     *   <li>向第三方查询支付状态，确认确实已扣款</li>
     *   <li>将订单状态从 EXPIRED 恢复为 PAID</li>
     *   <li>对业务订单：补写 Outbox 消息通知业务系统</li>
     *   <li>对充值订单：补入钱包余额</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> fixExpiredPaidOrder(String orderNo) {
        log.warn("【数据修复】开始修复 - orderNo: {}", orderNo);
        Map<String, Object> result = new HashMap<>();

        // 1. 查询订单
        PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在: " + orderNo);
        }
        result.put("orderNo", orderNo);
        result.put("currentStatus", order.getStatus());
        result.put(KEY_AMOUNT, order.getAmount());
        result.put("platform", order.getPlatform());
        result.put("bizNo", order.getBizNo());

        // 2. 状态检查：只修复 EXPIRED 状态的订单
        if (!PaymentOrderStatus.EXPIRED.getCode().equals(order.getStatus())
                && !PaymentOrderStatus.PENDING.getCode().equals(order.getStatus())) {
            throw new BusinessException("订单状态不是 EXPIRED/PENDING，无需修复，当前状态: " + order.getStatus());
        }

        // 3. 向第三方查询确认是否已支付
        boolean thirdPartyPaid = false;
        Map<String, Object> platformStatus = null;
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
            platformStatus = strategy.queryPayment(order);
            if (platformStatus != null) {
                String status = platformStatus.get(KEY_PLATFORM_STATUS) == null ? null
                        : platformStatus.get(KEY_PLATFORM_STATUS).toString();
                thirdPartyPaid = isPlatformPaid(status);
            }
        } catch (Exception e) {
            log.warn("【数据修复】查询第三方支付状态失败 - orderNo: {}, error: {}", orderNo, e.getMessage());
        }

        result.put("thirdPartyPaid", thirdPartyPaid);

        if (!thirdPartyPaid) {
            throw new BusinessException("第三方查询未确认已支付，请先在商户后台核实是否已扣款。"
                    + "如确认已扣款，可再次调用此接口（降级模式）并传入 force=true");
        }

        // 4. 修复订单状态：EXPIRED → PAID
        String callbackJson = null;
        if (platformStatus != null) {
            try {
                callbackJson = objectMapper.writeValueAsString(platformStatus);
            } catch (Exception e) {
                log.warn("【数据修复】序列化第三方状态失败 - orderNo: {}", orderNo, e);
            }
        }
        int updated = paymentOrderMapper.updateStatus(
                orderNo,
                PaymentOrderStatus.PAID.getCode(),
                callbackJson,
                null);
        if (updated != 1) {
            throw new BusinessException("修复订单状态失败");
        }

        // 重新查询获取更新后的 paidTime
        order = paymentOrderMapper.selectByOrderNo(orderNo);
        result.put("newStatus", order.getStatus());
        result.put("paidTime", order.getPaidTime());

        // 5. 按订单类型做后续处理
        if (isOrderPlanPayment(order)) {
            // 业务订单：补写 Outbox 消息通知业务系统
            PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
            msg.setOrderNo(orderNo);
            msg.setBusinessOrderNo(order.getBizNo());
            msg.setCustomerNo(order.getCustomerNo());
            msg.setAmountFen(order.getAmount());
            msg.setPlatform(order.getPlatform());
            msg.setBizType(order.getBizType());
            msg.setAppId(order.getAppId() != null ? order.getAppId() : BizTypeConstants.deriveAppId(order.getBizType()));
            msg.setPaidAt(order.getPaidTime() != null ? order.getPaidTime().toString() : LocalDateTime.now().toString());
            paymentEventProducer.sendOrderCompletedToOutbox(msg);
            result.put("outboxWritten", true);
            log.warn("【数据修复】业务订单已补写Outbox - orderNo: {}, bizNo: {}", orderNo, order.getBizNo());
        } else {
            // 充值订单：补入钱包余额
            String referenceNo = PaymentConstants.RECHARGE_PREFIX + orderNo;
            BigDecimal amountInYuan = order.getAmountInYuan();
            walletService.addBalance(
                    order.getCustomerNo(),
                    order.getBizType(),
                    amountInYuan,
                    "数据修复补入 - 订单号：" + orderNo,
                    PaymentConstants.TRANSACTION_TYPE_RECHARGE,
                    referenceNo);
            result.put("walletCredited", true);
            result.put("creditAmount", amountInYuan);
            log.warn("【数据修复】充值订单已补入钱包 - orderNo: {}, customerNo: {}, amount: {}",
                    orderNo, order.getCustomerNo(), amountInYuan);
        }

        log.warn("【数据修复】修复完成 - orderNo: {}, result: {}", orderNo, result);
        return result;
    }
}
