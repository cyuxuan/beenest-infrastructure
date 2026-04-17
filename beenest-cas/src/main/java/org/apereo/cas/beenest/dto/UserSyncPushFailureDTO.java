package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 用户同步推送失败记录
 */
@Data
public class UserSyncPushFailureDTO {

    private Long serviceId;
    private Long changeLogId;
    private String error;
    private int retryCount;
}
