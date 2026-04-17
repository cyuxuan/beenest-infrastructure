package org.apereo.cas.beenest.client.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 业务系统登录代理控制器。
 * <p>
 * 业务系统使用 starter 后，对外暴露与 CAS 相同的登录入口，
 * 由控制器接住请求并透明转发到 CAS Server。
 */
@RestController
@RequestMapping("${cas.client.business-login-proxy.base-path:/cas}")
public class CasBusinessLoginProxyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CasBusinessLoginProxyController.class);

    private final CasBusinessLoginProxyService proxyService;
    private final BearerTokenCache bearerTokenCache;
    private final BearerTokenRevocationService bearerTokenRevocationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CasBusinessLoginProxyController(CasBusinessLoginProxyService proxyService) {
        this(proxyService, null, null);
    }

    /**
     * 构造业务系统登录代理控制器。
     *
     * @param proxyService 登录代理服务
     * @param bearerTokenCacheProvider Bearer Token 缓存（可选）
     * @param bearerTokenRevocationServiceProvider Bearer Token 撤销服务（可选）
     */
    @Autowired
    public CasBusinessLoginProxyController(CasBusinessLoginProxyService proxyService,
                                           ObjectProvider<BearerTokenCache> bearerTokenCacheProvider,
                                           ObjectProvider<BearerTokenRevocationService> bearerTokenRevocationServiceProvider) {
        this.proxyService = proxyService;
        this.bearerTokenCache = bearerTokenCacheProvider != null ? bearerTokenCacheProvider.getIfAvailable() : null;
        this.bearerTokenRevocationService = bearerTokenRevocationServiceProvider != null
                ? bearerTokenRevocationServiceProvider.getIfAvailable()
                : null;
    }

    /**
     * 代理 APP 登录请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/app/login")
    public ResponseEntity<String> proxyAppLogin(@RequestBody(required = false) String body,
                                                HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/app/login");
    }

    /**
     * 代理 APP refresh 请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/app/refresh")
    public ResponseEntity<String> proxyAppRefresh(@RequestBody(required = false) String body,
                                                  HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/app/refresh");
    }

    /**
     * 代理微信小程序登录请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/miniapp/wechat/login")
    public ResponseEntity<String> proxyWechatLogin(@RequestBody(required = false) String body,
                                                   HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/miniapp/wechat/login");
    }

    /**
     * 代理抖音小程序登录请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/miniapp/douyin/login")
    public ResponseEntity<String> proxyDouyinLogin(@RequestBody(required = false) String body,
                                                   HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/miniapp/douyin/login");
    }

    /**
     * 代理支付宝小程序登录请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/miniapp/alipay/login")
    public ResponseEntity<String> proxyAlipayLogin(@RequestBody(required = false) String body,
                                                   HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/miniapp/alipay/login");
    }

    /**
     * 代理小程序 refresh 请求。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/miniapp/refresh")
    public ResponseEntity<String> proxyMiniAppRefresh(@RequestBody(required = false) String body,
                                                      HttpServletRequest request) {
        return proxyService.proxy(request, body, "/cas/miniapp/refresh");
    }

    /**
     * 代理 APP logout 请求，并在成功后撤销本地 Bearer Token 缓存。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/app/logout")
    public ResponseEntity<String> proxyAppLogout(@RequestBody(required = false) String body,
                                                 HttpServletRequest request) {
        ResponseEntity<String> response = proxyService.proxy(request, body, "/cas/app/logout");
        revokeLocalBearerCacheIfNeeded(body, response);
        return response;
    }

    /**
     * 代理小程序 logout 请求，并在成功后撤销本地 Bearer Token 缓存。
     *
     * @param body 请求体
     * @param request 当前请求
     * @return CAS Server 原始响应
     */
    @PostMapping("/miniapp/logout")
    public ResponseEntity<String> proxyMiniAppLogout(@RequestBody(required = false) String body,
                                                     HttpServletRequest request) {
        ResponseEntity<String> response = proxyService.proxy(request, body, "/cas/miniapp/logout");
        revokeLocalBearerCacheIfNeeded(body, response);
        return response;
    }

    /**
     * CAS logout 成功后，撤销本地 accessToken 缓存，避免注销后的 token 继续命中缓存。
     *
     * @param body 请求体
     * @param response CAS 响应
     */
    private void revokeLocalBearerCacheIfNeeded(String body, ResponseEntity<String> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            return;
        }

        String accessToken = extractAccessToken(body);
        String refreshToken = extractRefreshToken(body);
        if (bearerTokenCache != null && accessToken != null) {
            bearerTokenCache.revoke(accessToken);
        }
        if (bearerTokenRevocationService != null) {
            if (accessToken != null) {
                bearerTokenRevocationService.revokeAccessToken(accessToken);
            }
            if (refreshToken != null) {
                bearerTokenRevocationService.revokeRefreshToken(refreshToken);
            }
        }
    }

    /**
     * 从 logout 请求体中提取 accessToken。
     *
     * @param body JSON 请求体
     * @return accessToken，提取失败返回 null
     */
    private String extractAccessToken(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode accessTokenNode = root.path("accessToken");
            return accessTokenNode.isMissingNode() || accessTokenNode.isNull() ? null : accessTokenNode.asText(null);
        } catch (IOException e) {
            LOGGER.debug("解析 logout 请求体失败，跳过本地 bearer 撤销");
            return null;
        }
    }

    /**
     * 从 logout 请求体中提取 refreshToken。
     *
     * @param body JSON 请求体
     * @return refreshToken，提取失败返回 null
     */
    private String extractRefreshToken(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode refreshTokenNode = root.path("refreshToken");
            return refreshTokenNode.isMissingNode() || refreshTokenNode.isNull() ? null : refreshTokenNode.asText(null);
        } catch (IOException e) {
            LOGGER.debug("解析 logout 请求体失败，跳过 refreshToken 撤销");
            return null;
        }
    }
}
