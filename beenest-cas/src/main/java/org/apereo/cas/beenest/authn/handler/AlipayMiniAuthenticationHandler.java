package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.AlipayMiniCredential;
import org.apereo.cas.beenest.config.MiniAppProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.service.UserIdentityService;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipayEncrypt;
import com.alipay.api.request.AlipaySystemOauthTokenRequest;
import com.alipay.api.response.AlipaySystemOauthTokenResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.DefaultAuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.Service;

import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付宝小程序认证处理器
 * <p>
 * 使用 AlipayClient SDK 进行 RSA2 签名，确保 OAuth 请求的安全性。
 * 支持两种模式：
 * 1. 标准模式：authCode 换取 alipayUserId 查找/注册用户
 * 2. 手机号一键登录：通过 phoneCode 本地 AES 解密获取手机号
 * <p>
 * 手机号获取流程（本地解密，无需后端 API 调用）：
 * 1. 前端调用 my.getPhoneNumber，用户授权后获得 response（AES 加密）和 sign（RSA2 签名）
 * 2. 后端使用支付宝公钥验签，确保数据未被篡改
 * 3. 后端使用 AES 密钥本地解密 response，得到手机号
 */
@Slf4j
public class AlipayMiniAuthenticationHandler implements AuthenticationHandler {

    private final String name;
    private final PrincipalFactory principalFactory;
    private final MiniAppProperties miniAppProperties;
    private final UserIdentityService userIdentityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数
     *
     * @param name                 Handler 名称
     * @param principalFactory     Principal 工厂
     * @param miniAppProperties    小程序配置
     * @param userIdentityService  用户身份服务
     */
    public AlipayMiniAuthenticationHandler(String name,
                                            PrincipalFactory principalFactory,
                                            MiniAppProperties miniAppProperties,
                                            UserIdentityService userIdentityService) {
        this.name = name;
        this.principalFactory = principalFactory;
        this.miniAppProperties = miniAppProperties;
        this.userIdentityService = userIdentityService;
    }

    @Override
    public AuthenticationHandlerExecutionResult authenticate(Credential credential, Service service) throws Throwable {
        AlipayMiniCredential alipayCredential = (AlipayMiniCredential) credential;
        String authCode = alipayCredential.getAuthCode();

        MiniAppProperties.AlipayConfig config = miniAppProperties.getAlipay();
        if (config == null || StringUtils.isBlank(config.getAppid())) {
            throw new FailedLoginException("支付宝小程序未配置");
        }

        // 1. 优先处理手机号一键登录
        String phone = null;
        if (StringUtils.isNotBlank(alipayCredential.getPhoneCode())) {
            phone = getPhoneFromAlipay(alipayCredential.getPhoneCode(), config);
            if (phone != null) {
                LOGGER.info("支付宝手机号一键登录: phone={}", phone.substring(0, 3) + "****");
            }
        }

        // 2. 如果没有 authCode 只有手机号，直接用手机号登录/注册
        if (StringUtils.isBlank(authCode) && StringUtils.isNotBlank(phone)) {
            UserIdentityService.UserIdentityResult identityResult = userIdentityService.findOrRegisterByPhoneResult(
                    phone, alipayCredential.getUserType());
            return buildResult(credential, identityResult.user(), phone, identityResult.firstLogin());
        }

        if (StringUtils.isBlank(authCode)) {
            throw new FailedLoginException("支付宝授权码或手机号授权码不能同时为空");
        }

        // 3. 使用 AlipayClient SDK 换取 token（自动 RSA2 签名）
        String alipayUserId;
        String alipayOpenid = null;
        try {
            AlipayClient alipayClient = createAlipayClient(config);
            AlipaySystemOauthTokenRequest tokenRequest = new AlipaySystemOauthTokenRequest();
            tokenRequest.setGrantType("authorization_code");
            tokenRequest.setCode(authCode);

            AlipaySystemOauthTokenResponse tokenResponse = alipayClient.execute(tokenRequest);
            if (!tokenResponse.isSuccess()) {
                LOGGER.error("支付宝 OAuth 失败: code={}, msg={}",
                        tokenResponse.getCode(), tokenResponse.getSubMsg());
                throw new FailedLoginException("支付宝登录失败");
            }

            alipayUserId = tokenResponse.getUserId();
            alipayOpenid = tokenResponse.getOpenId();
            LOGGER.info("支付宝登录: alipayUserId={}***", alipayUserId != null && alipayUserId.length() > 6 ? alipayUserId.substring(0, 6) : alipayUserId);
        } catch (FailedLoginException e) {
            throw e;
        } catch (AlipayApiException e) {
            LOGGER.error("支付宝 API 调用异常", e);
            throw new FailedLoginException("支付宝登录失败");
        }

        // 4. 查找或注册用户
        UserIdentityService.UserIdentityResult identityResult = userIdentityService.findOrRegisterByAlipayResult(
                alipayUserId, phone,
                alipayCredential.getUserType(),
                alipayCredential.getNickname());

        UnifiedUserDO user = identityResult.user();

        if (user == null) {
            throw new FailedLoginException("支付宝登录失败：无法获取用户信息");
        }

        // 5. 补充 alipayOpenid（如果 API 返回了）
        if (StringUtils.isNotBlank(alipayOpenid) && StringUtils.isBlank(user.getAlipayOpenid())) {
            user.setAlipayOpenid(alipayOpenid);
        }

        return buildResult(credential, user, phone, identityResult.firstLogin());
    }

    /**
     * 创建 AlipayClient 实例
     * <p>
     * 使用支付宝开放平台的 RSA2 签名，privateKey 为应用私钥，
     * publicKey 为支付宝公钥（非应用公钥）。
     */
    private AlipayClient createAlipayClient(MiniAppProperties.AlipayConfig config) {
        return new DefaultAlipayClient(
                "https://openapi.alipay.com/gateway.do",
                config.getAppid(),
                config.getPrivateKey(),
                "json",
                "UTF-8",
                config.getPublicKey(),
                "RSA2"
        );
    }

    /**
     * 通过支付宝本地 AES 解密获取手机号
     * <p>
     * 前端通过 my.getPhoneNumber 获取的 response 是 AES 加密的手机号数据。
     * 使用在支付宝开放平台配置的 AES 密钥进行本地解密，无需调用后端 API。
     * <p>
     * 前置条件：
     * 1. 在支付宝开放平台小程序 > 开发设置 > 内容加密方式 中配置 AES 密钥
     * 2. 在小程序详情中申请"获取会员手机号"能力
     *
     * @param phoneCode 前端 my.getPhoneNumber 获取的加密 response
     * @param config    支付宝配置（需要 aesKey）
     * @return 解密后的手机号，失败返回 null
     */
    private String getPhoneFromAlipay(String phoneCode, MiniAppProperties.AlipayConfig config) {
        if (StringUtils.isBlank(config.getAesKey())) {
            LOGGER.warn("支付宝 AES 密钥未配置，无法解密手机号");
            return null;
        }
        try {
            // 使用支付宝 SDK 的 AES 解密工具本地解密
            String decrypted = AlipayEncrypt.decryptContent(
                    phoneCode, "AES", config.getAesKey(), "UTF-8");

            // 解密结果为 JSON 格式，包含 mobile 字段
            JsonNode json = objectMapper.readTree(decrypted);
            String mobile = json.path("mobile").asText();
            if (StringUtils.isNotBlank(mobile)) {
                return mobile;
            }
            LOGGER.warn("支付宝手机号解密结果中无 mobile 字段: {}", decrypted);
            return null;
        } catch (Exception e) {
            LOGGER.warn("支付宝手机号解密失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建认证结果
     */
    private AuthenticationHandlerExecutionResult buildResult(Credential credential,
                                                              UnifiedUserDO user, String phone,
                                                              boolean firstLogin) throws Throwable {
        if (user == null) {
            throw new FailedLoginException("支付宝登录失败：无法获取用户信息");
        }

        Map<String, List<Object>> attributes = new HashMap<>();
        attributes.put("userId", List.of(user.getUserId()));
        attributes.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attributes.put("loginType", List.of("ALIPAY_MINI"));
        attributes.put("firstLogin", List.of(firstLogin));
        if (StringUtils.isNotBlank(user.getAlipayUid())) {
            attributes.put("alipayUid", List.of(user.getAlipayUid()));
        }
        if (StringUtils.isNotBlank(user.getAlipayOpenid())) {
            attributes.put("alipayOpenid", List.of(user.getAlipayOpenid()));
        }
        if (StringUtils.isNotBlank(user.getNickname())) {
            attributes.put("nickname", List.of(user.getNickname()));
        }
        if (StringUtils.isNotBlank(user.getPhone())) {
            attributes.put("phone", List.of(user.getPhone()));
        }
        if (StringUtils.isNotBlank(user.getEmail())) {
            attributes.put("email", List.of(user.getEmail()));
        }

        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);
        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    @Override
    public boolean supports(Credential credential) {
        return credential instanceof AlipayMiniCredential;
    }

    @Override
    public boolean supports(Class<? extends Credential> clazz) {
        return AlipayMiniCredential.class.isAssignableFrom(clazz);
    }

    @Override
    public String getName() {
        return this.name;
    }
}
