package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * CAS 服务凭证实体。
 * <p>
 * 对应 cas_service_credential 表，仅保存密文、盐值、版本与状态，不保存明文 secret。
 */
@Data
public class CasServiceCredentialDO {

    private Long id;
    private Long serviceId;
    private String secretHash;
    private String secretSalt;
    private Long secretVersion;
    private String state;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
