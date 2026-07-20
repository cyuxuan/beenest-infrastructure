package club.beenest.payment.paymentorder.mq.producer;

import club.beenest.payment.shared.mq.PaymentMqConstants;
import club.beenest.payment.shared.mq.MessageSignUtil;
import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.RefundCompletedMessage;
import club.beenest.payment.wallet.mq.BalanceChangedMessage;
import club.beenest.payment.withdraw.mq.WithdrawCompletedMessage;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.mapper.OutboxMessageMapper;
import club.beenest.payment.shared.domain.entity.OutboxMessage;
import club.beenest.payment.shared.service.AppCredentialService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付事件消息生产者
 * 支付中台通过此生产者发送消息到主业务服务
 *
 * <p>采用 Outbox 模式保证消息最终一致性：
 * <ol>
 *   <li>先尝试直接发送 MQ 消息</li>
 *   <li>发送失败时写入 outbox 表，由定时任务补偿重发</li>
 * </ol>
 *
 * <p>签名策略：通过 bizType 推导 appId，使用 per-app MQ 密钥签名。</p>
 *
 * @author System
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final RabbitTemplate rabbitTemplate;
    private final OutboxMessageMapper outboxMessageMapper;
    private final ObjectMapper objectMapper;
    private final AppCredentialService appCredentialService;

    /**
     * 发送支付订单完成消息
     */
    public void sendOrderCompleted(PaymentOrderCompletedMessage message) {
        message.setMessageId(generateMessageId("ORDER-COMPLETE"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signOrderMessage(mqSecret,
                message.getMessageId(), message.getOrderNo(), message.getBusinessOrderNo(),
                message.getCustomerNo(), message.getAmountFen(), message.getPlatform(),
                message.getBizType()));
        send(PaymentMqConstants.RK_PAYMENT_ORDER_COMPLETED, message);
    }

    /**
     * 发送支付订单取消消息
     */
    public void sendOrderCancelled(PaymentOrderCompletedMessage message) {
        message.setMessageId(generateMessageId("ORDER-CANCEL"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signOrderMessage(mqSecret,
                message.getMessageId(), message.getOrderNo(), message.getBusinessOrderNo(),
                message.getCustomerNo(), message.getAmountFen(), message.getPlatform(),
                message.getBizType()));
        send(PaymentMqConstants.RK_PAYMENT_ORDER_CANCELLED, message);
    }

    /**
     * 发送退款完成消息
     */
    public void sendRefundCompleted(RefundCompletedMessage message) {
        message.setMessageId(generateMessageId("REFUND-COMPLETE"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signRefundMessage(mqSecret,
                message.getMessageId(), message.getRefundNo(), message.getOrderNo(),
                message.getBusinessOrderNo(), message.getStatus(), message.getBizType()));
        send(PaymentMqConstants.RK_REFUND_COMPLETED, message);
    }

    /**
     * 发送提现完成消息
     */
    public void sendWithdrawCompleted(WithdrawCompletedMessage message) {
        message.setMessageId(generateMessageId("WITHDRAW-COMPLETE"));
        String mqSecret = resolveMqSecretByAppId(message.getAppId());
        message.setSign(MessageSignUtil.signWithdrawMessage(mqSecret,
                message.getMessageId(), message.getRequestNo(), message.getCustomerNo(),
                message.getActualAmountFen(), message.getStatus(), message.getAppId()));
        send(PaymentMqConstants.RK_WITHDRAW_COMPLETED, message);
    }

    /**
     * 发送余额变动消息
     */
    public void sendBalanceChanged(BalanceChangedMessage message) {
        message.setMessageId(generateMessageId("BALANCE-CHANGE"));
        String mqSecret = resolveMqSecretByAppId(message.getAppId());
        message.setSign(MessageSignUtil.signBalanceMessage(mqSecret,
                message.getMessageId(), message.getCustomerNo(), message.getWalletNo(),
                message.getBeforeBalanceFen(), message.getAfterBalanceFen(),
                message.getChangeAmountFen(), message.getTransactionType(),
                message.getAppId()));
        send(PaymentMqConstants.RK_BALANCE_CHANGED, message);
    }

    /**
     * 事务内直接写入 Outbox：余额变动消息
     *
     * <p><b>安全关键</b>：替代 afterCommit + MQ 直发，保证消息不丢失。
     * 由 {@link club.beenest.payment.shared.scheduler.OutboxMessageScheduler} 补偿发送。</p>
     */
    public void sendBalanceChangedToOutbox(BalanceChangedMessage message) {
        message.setMessageId(generateMessageId("BALANCE-CHANGE"));
        String mqSecret = resolveMqSecretByAppId(message.getAppId());
        message.setSign(MessageSignUtil.signBalanceMessage(mqSecret,
                message.getMessageId(), message.getCustomerNo(), message.getWalletNo(),
                message.getBeforeBalanceFen(), message.getAfterBalanceFen(),
                message.getChangeAmountFen(), message.getTransactionType(),
                message.getAppId()));
        saveToOutbox(PaymentMqConstants.RK_BALANCE_CHANGED, message,
                message.getMessageId(), "事务内Outbox直写，等待Scheduler补偿发送");
        log.info("余额变动消息已写入Outbox - customerNo: {}, walletNo: {}, messageId: {}",
                message.getCustomerNo(), message.getWalletNo(), message.getMessageId());
    }

    /**
     * 发送消息：先尝试直接发送 MQ，失败后写入 outbox 表等待补偿重发
     */
    private void send(String routingKey, Object message) {
        String messageId = extractMessageId(message);
        try {
            rabbitTemplate.convertAndSend(PaymentMqConstants.PAYMENT_EXCHANGE, routingKey, message);
            log.info("MQ 消息发送成功: routingKey={}, messageId={}", routingKey, messageId);
        } catch (Exception e) {
            log.error("MQ 消息直接发送失败，写入outbox等待补偿: routingKey={}, messageId={}", routingKey, messageId, e);
            saveToOutbox(routingKey, message, messageId, e.getMessage());
        }
    }

    // ==================== 事务内 Outbox 直写方法 ====================

    /**
     * 事务内直接写入 Outbox：支付订单完成消息
     *
     * <p>与 {@link #sendOrderCompleted} 不同，此方法不尝试 MQ 直发，
     * 而是直接写入 Outbox 表，由 {@link club.beenest.payment.shared.scheduler.OutboxMessageScheduler} 补偿发送。</p>
     *
     * <p><b>安全关键</b>：此方法必须在 {@code @Transactional} 事务内调用，
     * 确保 Outbox 写入与业务数据更新在同一事务中原子提交。</p>
     */
    public void sendOrderCompletedToOutbox(PaymentOrderCompletedMessage message) {
        message.setMessageId(generateMessageId("ORDER-COMPLETE"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signOrderMessage(mqSecret,
                message.getMessageId(), message.getOrderNo(), message.getBusinessOrderNo(),
                message.getCustomerNo(), message.getAmountFen(), message.getPlatform(),
                message.getBizType()));
        saveToOutbox(PaymentMqConstants.RK_PAYMENT_ORDER_COMPLETED, message,
                message.getMessageId(), "事务内Outbox直写，等待Scheduler补偿发送");
        log.info("支付订单完成消息已写入Outbox - orderNo: {}, bizNo: {}, messageId: {}",
                message.getOrderNo(), message.getBusinessOrderNo(), message.getMessageId());
    }

    /**
     * 事务内直接写入 Outbox：支付订单取消消息
     */
    public void sendOrderCancelledToOutbox(PaymentOrderCompletedMessage message) {
        message.setMessageId(generateMessageId("ORDER-CANCEL"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signOrderMessage(mqSecret,
                message.getMessageId(), message.getOrderNo(), message.getBusinessOrderNo(),
                message.getCustomerNo(), message.getAmountFen(), message.getPlatform(),
                message.getBizType()));
        saveToOutbox(PaymentMqConstants.RK_PAYMENT_ORDER_CANCELLED, message,
                message.getMessageId(), "事务内Outbox直写，等待Scheduler补偿发送");
        log.info("支付订单取消消息已写入Outbox - orderNo: {}, bizNo: {}, messageId: {}",
                message.getOrderNo(), message.getBusinessOrderNo(), message.getMessageId());
    }

    /**
     * 事务内直接写入 Outbox：退款完成消息
     */
    public void sendRefundCompletedToOutbox(RefundCompletedMessage message) {
        message.setMessageId(generateMessageId("REFUND-COMPLETE"));
        String mqSecret = resolveMqSecret(message.getBizType());
        message.setAppId(resolveAppId(message.getBizType()));
        message.setSign(MessageSignUtil.signRefundMessage(mqSecret,
                message.getMessageId(), message.getRefundNo(), message.getOrderNo(),
                message.getBusinessOrderNo(), message.getStatus(), message.getBizType()));
        saveToOutbox(PaymentMqConstants.RK_REFUND_COMPLETED, message,
                message.getMessageId(), "事务内Outbox直写，等待Scheduler补偿发送");
        log.info("退款完成消息已写入Outbox - refundNo: {}, messageId: {}", message.getRefundNo(), message.getMessageId());
    }

    // ==================== 密钥解析 ====================

    /**
     * 根据 bizType 推导 appId（支付订单/退款消息仍使用 bizType）
     */
    private String resolveAppId(String bizType) {
        return BizTypeConstants.deriveAppId(bizType);
    }

    /**
     * 解析 MQ 签名密钥：通过 bizType 推导 appId，获取 per-app 密钥
     *
     * @param bizType 业务类型
     * @return MQ 明文密钥
     * @throws IllegalStateException 无法获取密钥时抛出
     */
    private String resolveMqSecret(String bizType) {
        String mqSecret = appCredentialService.getMqSecretByBizType(bizType);
        if (StringUtils.isNotBlank(mqSecret)) {
            return mqSecret;
        }
        throw new IllegalStateException("无法获取 MQ 签名密钥: bizType=" + bizType
                + "，请检查 ds_app_credential 表中对应 appId 的 mq_secret 配置");
    }

    /**
     * 解析 MQ 签名密钥：通过 appId 直接获取 per-app 密钥
     *
     * @param appId 业务系统标识
     * @return MQ 明文密钥
     * @throws IllegalStateException 无法获取密钥时抛出
     */
    private String resolveMqSecretByAppId(String appId) {
        if (StringUtils.isBlank(appId)) {
            throw new IllegalStateException("appId 为空，无法获取 MQ 签名密钥");
        }
        String mqSecret = appCredentialService.getMqSecret(appId);
        if (StringUtils.isNotBlank(mqSecret)) {
            return mqSecret;
        }
        throw new IllegalStateException("无法获取 MQ 签名密钥: appId=" + appId
                + "，请检查 ds_app_credential 表中对应 appId 的 mq_secret 配置");
    }

    // ==================== 内部方法 ====================

    /**
     * 写入 outbox 表，由定时任务补偿发送
     */
    private void saveToOutbox(String routingKey, Object message, String messageId, String errorMessage) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            OutboxMessage outbox = new OutboxMessage();
            outbox.setMessageId(messageId);
            outbox.setExchange(PaymentMqConstants.PAYMENT_EXCHANGE);
            outbox.setRoutingKey(routingKey);
            outbox.setPayload(payload);
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);
            outbox.setMaxRetry(5);
            outbox.setNextRetryTime(LocalDateTime.now().plusSeconds(30));
            outbox.setErrorMessage(errorMessage != null && errorMessage.length() > 500
                    ? errorMessage.substring(0, 500) : errorMessage);
            outboxMessageMapper.insert(outbox);
        } catch (Exception ex) {
            log.error("写入outbox表也失败，消息可能丢失: messageId={}", messageId, ex);
        }
    }

    private String extractMessageId(Object message) {
        if (message instanceof PaymentOrderCompletedMessage msg) return msg.getMessageId();
        if (message instanceof RefundCompletedMessage msg) return msg.getMessageId();
        if (message instanceof WithdrawCompletedMessage msg) return msg.getMessageId();
        if (message instanceof BalanceChangedMessage msg) return msg.getMessageId();
        return "unknown";
    }

    private String generateMessageId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
