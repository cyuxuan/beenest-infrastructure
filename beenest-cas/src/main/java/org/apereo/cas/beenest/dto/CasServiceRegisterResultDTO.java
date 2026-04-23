package org.apereo.cas.beenest.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * CAS 服务注册结果。
 * <p>
 * 在返回服务摘要信息的同时，额外携带一次性明文 secret 和版本号。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class CasServiceRegisterResultDTO extends CasServiceSummaryDTO {

    private String plainSecret;
    private Long secretVersion;
}
