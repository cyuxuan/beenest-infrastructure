package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * CAS 应用摘要信息
 */
@Data
public class CasServiceSummaryDTO {

    private Long id;
    private String name;
    private String serviceId;
    private String description;
}
