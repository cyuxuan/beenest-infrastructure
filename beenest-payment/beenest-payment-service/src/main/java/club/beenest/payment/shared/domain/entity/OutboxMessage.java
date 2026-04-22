package club.beenest.payment.shared.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * MQ消息Outbox实体
 * 保证消息发送的最终一致性
 */
@Data
public class OutboxMessage {
    private Long id;
    private String messageId;
    private String exchange;
    private String routingKey;
    private String payload;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
