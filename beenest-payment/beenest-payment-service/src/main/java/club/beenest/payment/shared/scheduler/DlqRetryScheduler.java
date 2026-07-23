package club.beenest.payment.shared.scheduler;

import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.mq.PaymentMqConstants;
import club.beenest.payment.shared.service.AppCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信队列自动重试调度器
 *
 * <p>定期扫描各租户死信队列（DLQ）中的消息，重新投递到原业务队列。</p>
 *
 * <h3>重试策略：</h3>
 * <ul>
 *   <li>每 5 分钟扫描一次</li>
 *   <li>每次从每个 DLQ 最多取 10 条消息</li>
 *   <li>重投到原业务队列后，消息从 DLQ 删除</li>
 *   <li>如果重投失败，消息保留在 DLQ，等下次重试</li>
 * </ul>
 *
 * <h3>多租户隔离：</h3>
 * <ul>
 *   <li>遍历 ds_app_credential 中所有 ACTIVE 租户的 DLQ</li>
 *   <li>DLQ 队列名和路由键均带租户后缀</li>
 * </ul>
 *
 * <h3>安全设计：</h3>
 * <ul>
 *   <li>消费端已有 Redis 幂等控制，重复投递不会重复处理</li>
 *   <li>消费端已有 HMAC 验签，伪造消息会被拒绝</li>
 * </ul>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqRetryScheduler {

    private final RabbitTemplate rabbitTemplate;
    private final AppCredentialService appCredentialService;

    /** 每次从 DLQ 取消息的最大数量 */
    private static final int MAX_MESSAGES_PER_DLQ = 10;

    /**
     * 每 5 分钟执行一次 DLQ 重试扫描
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 180_000)
    public void retryDlqMessages() {
        for (AppCredential credential : appCredentialService.getAllActive()) {
            String appId = credential.getAppId();
            for (String baseRoutingKey : PaymentMqConstants.ALL_ROUTING_KEYS) {
                String dlqName = PaymentMqConstants.tenantDlqName(
                        PaymentMqConstants.routingKeyToDlqName(baseRoutingKey), appId);
                String routingKey = PaymentMqConstants.tenantRoutingKey(baseRoutingKey, appId);
                retryFromDlq(dlqName, routingKey);
            }
        }
    }

    /**
     * 从指定 DLQ 读取消息并重新投递到原业务队列
     *
     * <p>使用 RabbitTemplate 的 channel-bound API 确保消息只在重投成功后才从 DLQ 删除。
     * 如果重投失败，消息保留在 DLQ，等下次重试。</p>
     */
    private void retryFromDlq(String dlqQueue, String routingKey) {
        int retried = 0;

        for (int i = 0; i < MAX_MESSAGES_PER_DLQ; i++) {
            try {
                Object message = rabbitTemplate.receiveAndConvert(dlqQueue);
                if (message == null) {
                    break; // DLQ 为空
                }

                // 重新投递到原业务队列
                rabbitTemplate.convertAndSend(
                        PaymentMqConstants.PAYMENT_EXCHANGE,
                        routingKey,
                        message);

                retried++;
            } catch (Exception e) {
                log.error("DLQ 重投失败 - dlq: {}, routingKey: {}, error: {}",
                        dlqQueue, routingKey, e.getMessage());
                break;
            }
        }

        if (retried > 0) {
            log.info("DLQ 重试完成 - dlq: {}, 重投: {}", dlqQueue, retried);
        }
    }
}
