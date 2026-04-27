package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.CasUserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

/**
 * CAS 原生票据验证器。
 * <p>
 * 负责调用 CAS 原生 REST + serviceValidate 流程，将 TGT 解析为当前用户会话。
 */
@Slf4j
public class CasNativeTicketValidator {

    private final CasNativeTicketValidationService validationService;

    /**
     * 构造验证器。
     *
     * @param properties CAS 客户端配置
     */
    public CasNativeTicketValidator(CasSecurityProperties properties) {
        this(properties, new RestTemplate());
    }

    /**
     * 构造验证器。
     *
     * @param properties CAS 客户端配置
     * @param restTemplate HTTP 客户端
     */
    CasNativeTicketValidator(CasSecurityProperties properties, RestTemplate restTemplate) {
        this.validationService = new CasNativeTicketValidationService(properties, restTemplate);
    }

    /**
     * 验证 accessToken (TGT) 有效性。
     *
     * @param accessToken TGT ID（Bearer token 的值）
     * @return 用户会话信息，验证失败返回 null
     */
    public CasUserSession validate(String accessToken) {
        return validationService.validate(accessToken);
    }
}
