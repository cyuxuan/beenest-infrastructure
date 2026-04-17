package org.apereo.cas.beenest.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户同步 Webhook payload
 */
@Data
public class UserSyncWebhookPayloadDTO {

    private String eventType;
    private String userId;
    private Map<String, Object> newData;
    private LocalDateTime timestamp;
}
