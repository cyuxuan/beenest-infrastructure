package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户变更日志实体
 * <p>
 * 对应 cas_user_change_log 表，记录用户数据的增删改，供下游应用服务拉取同步。
 */
@Data
public class CasUserChangeLog {

    private Long id;
    private String userId;
    private String changeType;
    private String oldData;
    private String newData;
    private Boolean synced;
    private LocalDateTime createdTime;
}
