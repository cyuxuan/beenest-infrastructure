package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.TokenValidationResponseDTO;
import org.apereo.cas.beenest.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 验证 REST 控制器
 * <p>
 * 提供 accessToken (TGT) 的轻量级验证端点，供 CAS Client
 * 在 Bearer Token 认证模式下验证 TGT 有效性并获取用户信息。
 * <p>
 * 使用 POST 方法 + form body 传输 accessToken，避免 TGT 泄露到 URL 日志中。
 * <p>
 * 与标准 CAS /serviceValidate 不同，此端点：
 * - 不需要 service 参数和 ST
 * - 直接查询 TicketRegistry（Redis 后端），性能更好
 * - 返回标准 JSON 格式，便于 CAS Client 解析
 * <p>
 * 安全注意：此端点应限制为内网访问（通过 Nginx 或 Spring Security 配置）。
 */
@Slf4j
@RestController
@RequestMapping("/token")
@RequiredArgsConstructor
public class TokenValidationController {

    private final TicketRegistry ticketRegistry;

    @Value("${beenest.token.validation-secret:}")
    private String validationSecret;

    /**
     * 验证 accessToken (TGT) 有效性
     * <p>
     * CAS Client 在收到携带 {@code Authorization: Bearer {tgt}} 的请求后，
     * 调用此端点验证 TGT 是否仍然有效，并获取用户信息构建本地会话。
     * <p>
     * 使用 POST 方法传输 accessToken，避免 token 出现在 URL、访问日志、代理日志中。
     *
     * @param accessToken TGT ID（Bearer token 的值）
     * @return 用户信息，包含 userId 和 attributes
     */
    @PostMapping("/validate")
    public R<TokenValidationResponseDTO> validate(@RequestParam String accessToken,
                                                  @RequestHeader(value = "X-CAS-Token-Secret", required = false) String requestSecret) {
        if (StringUtils.isBlank(validationSecret) || !validationSecret.equals(requestSecret)) {
            throw new BusinessException(401, "未授权访问");
        }
        if (StringUtils.isBlank(accessToken)) {
            return R.fail(400, "accessToken 不能为空");
        }

        try {
            TicketGrantingTicket tgt = ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class);
            if (tgt == null || tgt.isExpired()) {
                return R.fail(401, "accessToken 已过期或无效");
            }

            Principal principal = tgt.getAuthentication().getPrincipal();

            TokenValidationResponseDTO data = new TokenValidationResponseDTO();
            data.setUserId(principal.getId());
            data.setAttributes(principal.getAttributes());

            return R.ok(data);
        } catch (Exception e) {
            LOGGER.debug("TGT 验证失败: {}", e.getMessage());
            return R.fail(401, "accessToken 已过期或无效");
        }
    }
}
