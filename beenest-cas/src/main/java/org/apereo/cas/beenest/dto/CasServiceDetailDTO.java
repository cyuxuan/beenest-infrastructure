package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * CAS 应用详情信息
 */
@Data
public class CasServiceDetailDTO {

    private Long id;
    private String name;
    private String serviceId;
    private String description;
    private String logoutUrl;
    private Object attributeReleasePolicy;
    private Object authenticationPolicy;
    private Object accessStrategy;
}
