package org.apereo.cas.beenest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 应用可用认证方式
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CasServiceAuthMethodDTO {

    private String code;
    private String name;
    private String handler;
}
