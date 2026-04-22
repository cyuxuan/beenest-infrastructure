package club.beenest.payment.shared.scheduler;

import club.beenest.payment.shared.mapper.OutboxMessageMapper;
import club.beenest.payment.shared.domain.entity.OutboxMessage;
import club.beenest.payment.shared.mq.MessageSignUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 消息补偿定时任务
 * 每隔 30 秒扫描待重发的消息并尝试发送
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>重发前基于 payload 关键字段重新计算 HMAC 签名，防止 payload 被篡改后发出伪造消息</li>
 *   <li>如果签名重算失败（字段缺失等），标记消息为 FAILED，要求人工介入</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageScheduler {

    private final OutboxMessageMapper outboxMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void retryPendingMessages() {
        List<OutboxMessage> messages;
        try {
            messages = outboxMessageMapper.selectPendingForRetry(BATCH_SIZE);
        } catch (Exception e) {
            log.error("扫描 outbox 待重发消息失败", e);
            return;
        }

        if (messages.isEmpty()) {
            return;
        }

        log.info("扫描到 {} 条 outbox 待重发消息", messages.size());

        for (OutboxMessage msg : messages) {
            try {
                // 重发前重新计算签名，确保 payload 未被篡改
                String safePayload = recomputeSign(msg);
                if (safePayload == null) {
                    // 签名重算失败（消息类型未知或关键字段缺失），标记为 FAILED
                    outboxMessageMapper.updateStatus(msg.getMessageId(), "FAILED",
                            "签名重算失败，消息类型不支持或字段缺失");
                    log.error("Outbox 消息签名重算失败，标记为 FAILED: messageId={}, routingKey={}",
                            msg.getMessageId(), msg.getRoutingKey());
                    continue;
                }

                rabbitTemplate.convertAndSend(
                        msg.getExchange() != null ? msg.getExchange() : "payment.exchange",
                        msg.getRoutingKey(),
                        safePayload);

                outboxMessageMapper.updateStatus(msg.getMessageId(), "SENT", null);
                log.info("Outbox 消息补偿发送成功: messageId={}, retryCount={}", msg.getMessageId(), msg.getRetryCount());
            } catch (Exception e) {
                int nextRetry = msg.getRetryCount() + 1;
                if (nextRetry >= msg.getMaxRetry()) {
                    outboxMessageMapper.updateStatus(msg.getMessageId(), "FAILED",
                            "超过最大重试次数: " + e.getMessage());
                    log.error("Outbox 消息超过最大重试次数，标记为 FAILED: messageId={}", msg.getMessageId(), e);
                } else {
                    // 指数退避：30s, 60s, 120s, 240s...
                    long delaySeconds = (long) (30 * Math.pow(2, Math.min(nextRetry, 4)));
                    LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(delaySeconds);
                    outboxMessageMapper.incrementRetry(msg.getMessageId(), nextRetryTime,
                            "重发失败(" + nextRetry + "): " + (e.getMessage() != null && e.getMessage().length() > 200
                                    ? e.getMessage().substring(0, 200) : e.getMessage()));
                    log.warn("Outbox 消息重发失败，等待下次重试: messageId={}, retryCount={}, nextRetry={}s",
                            msg.getMessageId(), nextRetry, delaySeconds);
                }
            }
        }
    }

    /**
     * 基于 payload 重新计算 HMAC 签名。
     * 反序列化 JSON，根据 routingKey 判断消息类型，调用对应的签名方法，更新 sign 字段。
     *
     * @return 更新签名后的 JSON payload；如果签名重算失败返回 null
     */
    private String recomputeSign(OutboxMessage msg) {
        try {
            JsonNode root = objectMapper.readTree(msg.getPayload());
            String routingKey = msg.getRoutingKey();
            String messageId = textVal(root, "messageId");

            if (routingKey == null || messageId == null) {
                return null;
            }

            String newSign;
            if (routingKey.contains("order.completed") || routingKey.contains("order.cancelled")) {
                newSign = MessageSignUtil.signOrderMessage(
                        messageId,
                        textVal(root, "orderNo"),
                        textVal(root, "businessOrderNo"),
                        textVal(root, "customerNo"),
                        longVal(root, "amountFen"),
                        textVal(root, "platform"),
                        textVal(root, "bizType"));
            } else if (routingKey.contains("refund.completed")) {
                newSign = MessageSignUtil.signRefundMessage(
                        messageId,
                        textVal(root, "refundNo"),
                        textVal(root, "orderNo"),
                        textVal(root, "businessOrderNo"),
                        textVal(root, "status"),
                        textVal(root, "bizType"));
            } else if (routingKey.contains("withdraw.completed")) {
                newSign = MessageSignUtil.signWithdrawMessage(
                        messageId,
                        textVal(root, "requestNo"),
                        textVal(root, "customerNo"),
                        longVal(root, "actualAmountFen"),
                        textVal(root, "status"),
                        textVal(root, "bizType"));
            } else if (routingKey.contains("balance.changed")) {
                newSign = MessageSignUtil.signBalanceMessage(
                        messageId,
                        textVal(root, "customerNo"),
                        textVal(root, "walletNo"),
                        longVal(root, "beforeBalanceFen"),
                        longVal(root, "afterBalanceFen"),
                        longVal(root, "changeAmountFen"),
                        textVal(root, "transactionType"),
                        textVal(root, "bizType"));
            } else {
                log.warn("未知的 outbox 消息 routingKey，跳过签名重算: {}", routingKey);
                return msg.getPayload();
            }

            // 更新 sign 字段
            if (root instanceof ObjectNode objectNode) {
                objectNode.put("sign", newSign);
                return objectMapper.writeValueAsString(objectNode);
            }
            return msg.getPayload();
        } catch (Exception e) {
            log.error("Outbox 消息签名重算异常: messageId={}, error={}", msg.getMessageId(), e.getMessage(), e);
            return null;
        }
    }

    private static String textVal(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static Long longVal(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull() && child.isNumber()) ? child.asLong() : null;
    }
}
