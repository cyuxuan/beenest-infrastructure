package club.beenest.payment.shared.scheduler;

import club.beenest.payment.shared.mapper.OutboxMessageMapper;
import club.beenest.payment.shared.domain.entity.OutboxMessage;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageScheduler {

    private final OutboxMessageMapper outboxMessageMapper;
    private final RabbitTemplate rabbitTemplate;

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
                rabbitTemplate.convertAndSend(
                        msg.getExchange() != null ? msg.getExchange() : "payment.exchange",
                        msg.getRoutingKey(),
                        msg.getPayload());

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
}
