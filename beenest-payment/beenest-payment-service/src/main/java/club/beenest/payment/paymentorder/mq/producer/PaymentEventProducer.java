package club.beenest.payment.paymentorder.mq.producer;

import club.beenest.payment.shared.mq.PaymentMqConstants;
import club.beenest.payment.shared.mq.MessageSignUtil;
import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.RefundCompletedMessage;
import club.beenest.payment.wallet.mq.BalanceChangedMessage;
import club.beenest.payment.withdraw.mq.WithdrawCompletedMessage;
import club.beenest.payment.shared.mapper.OutboxMessageMapper;
import club.beenest.payment.shared.domain.entity.OutboxMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final RabbitTemplate rabbitTemplate;
    private final OutboxMessageMapper outboxMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 发送支付订单完成消息
     */
    public void sendOrderCompleted(PaymentOrderCompletedMessage message) {
        message.setMessageId(generateMessageId("ORDER-COMPLETE"));
        message.setSign(MessageSignUtil.signOrderMessage(
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
        message.setSign(MessageSignUtil.signOrderMessage(
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
        message.setSign(MessageSignUtil.signRefundMessage(
                message.getMessageId(), message.getRefundNo(), message.getOrderNo(),
                message.getBusinessOrderNo(), message.getStatus(), message.getBizType()));
        send(PaymentMqConstants.RK_REFUND_COMPLETED, message);
    }

    /**
     * 发送提现完成消息
     */
    public void sendWithdrawCompleted(WithdrawCompletedMessage message) {
        message.setMessageId(generateMessageId("WITHDRAW-COMPLETE"));
        message.setSign(MessageSignUtil.signWithdrawMessage(
                message.getMessageId(), message.getRequestNo(), message.getCustomerNo(),
                message.getActualAmountFen(), message.getStatus(), message.getBizType()));
        send(PaymentMqConstants.RK_WITHDRAW_COMPLETED, message);
    }

    /**
     * 发送余额变动消息
     */
    public void sendBalanceChanged(BalanceChangedMessage message) {
        message.setMessageId(generateMessageId("BALANCE-CHANGE"));
        message.setSign(MessageSignUtil.signBalanceMessage(
                message.getMessageId(), message.getCustomerNo(), message.getWalletNo(),
                message.getBeforeBalanceFen(), message.getAfterBalanceFen(),
                message.getChangeAmountFen(), message.getTransactionType(),
                message.getBizType()));
        send(PaymentMqConstants.RK_BALANCE_CHANGED, message);
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
