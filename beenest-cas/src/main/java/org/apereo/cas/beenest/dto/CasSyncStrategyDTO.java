package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 同步策略配置 DTO
 */
@Data
public class CasSyncStrategyDTO {

    private Boolean pushEnabled;
    private String pushUrl;
    private String pushSecret;
    private String pushEvents;
    private Boolean pullEnabled;
    private Integer maxRetries;
    private Integer retryInterval;
}
