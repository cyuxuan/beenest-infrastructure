package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步策略配置实体
 * <p>
 * 对应 cas_sync_strategy 表，定义每个应用的用户数据同步策略。
 */
@Data
public class CasSyncStrategy {

    private Long id;
    private Long serviceId;

    /** 是否启用推送 */
    private Boolean pushEnabled;

    /** Webhook 推送 URL */
    private String pushUrl;

    /** Webhook 签名密钥 */
    private String pushSecret;

    /** 订阅的变更类型（逗号分隔） */
    private String pushEvents;

    /** 是否启用拉取 */
    private Boolean pullEnabled;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 重试间隔（秒） */
    private Integer retryInterval;

    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
