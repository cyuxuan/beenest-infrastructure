package club.beenest.iam.authz.webauthn;

import club.beenest.iam.persistence.entity.WebAuthnCredential;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebAuthn 选项构造器。
 * <p>选项按浏览器 {@code PublicKeyCredentialCreationOptions} /
 * {@code PublicKeyCredentialRequestOptions} 的 JSON 结构直接组装成 Map,
 * 不经过平台自有的 DTO:这些字段名由规范固定,多一层转换只多一处
 * 拼写错误的机会,而字段名拼错在浏览器侧表现为"参数无效",排查成本很高。</p>
 */
@Component
public class WebAuthnOptionsFactory {

    /** 凭证类型:公钥 */
    private static final String TYPE_PUBLIC_KEY = "public-key";
    /** 证明传递偏好:不要求(平台不校验证书链) */
    private static final String ATTESTATION_NONE = "none";
    /** 常驻密钥偏好:优先(无用户名登录需要) */
    private static final String RESIDENT_KEY_PREFERRED = "preferred";
    /** 常驻密钥偏好:不要求 */
    private static final String RESIDENT_KEY_DISCOURAGED = "discouraged";

    /** 支持的签名算法:ES256(默认)与 RS256(兼容老认证器) */
    private static final int ALG_ES256 = -7;
    private static final int ALG_RS256 = -257;

    private final WebAuthnProperties properties;

    /**
     * 构造器注入。
     *
     * @param properties Passkey 配置
     */
    public WebAuthnOptionsFactory(WebAuthnProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造注册选项。
     *
     * @param challenge        本次挑战
     * @param userHandle       用户句柄(认证器保存的账号标识)
     * @param username         登录名(认证器列表里展示)
     * @param displayName      展示名(可为空,空则回落登录名)
     * @param excludeCredentials 已绑定的凭证(认证器据此拒绝重复绑定同一把钥匙)
     * @return 可直接下发给浏览器的选项
     */
    public Map<String, Object> registrationOptions(byte[] challenge, byte[] userHandle,
                                                   String username, String displayName,
                                                   List<WebAuthnCredential> excludeCredentials) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", WebAuthnCodec.encode(challenge));

        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("id", properties.getRpId());
        rp.put("name", properties.getRpName());
        options.put("rp", rp);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", WebAuthnCodec.encode(userHandle));
        user.put("name", username);
        user.put("displayName", displayName == null || displayName.isBlank() ? username : displayName);
        options.put("user", user);

        options.put("pubKeyCredParams", publicKeyCredentialParams());
        options.put("timeout", properties.getTimeoutMillis());
        options.put("attestation", ATTESTATION_NONE);
        options.put("authenticatorSelection", authenticatorSelection(true));
        options.put("excludeCredentials", descriptors(excludeCredentials));
        return options;
    }

    /**
     * 构造登录选项。
     *
     * @param challenge    本次挑战
     * @param credentials  允许使用的凭证;为空表示无用户名登录(由认证器自选)
     * @return 可直接下发给浏览器的选项
     */
    public Map<String, Object> authenticationOptions(byte[] challenge, List<WebAuthnCredential> credentials) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", WebAuthnCodec.encode(challenge));
        options.put("rpId", properties.getRpId());
        options.put("timeout", properties.getTimeoutMillis());
        options.put("userVerification", userVerification());
        // 无用户名登录时不下发 allowCredentials:浏览器据此弹出"选择密钥"而不是
        // 只认平台指定的那把。传空数组与不传语义不同,空数组会被理解成"没有可用凭证"
        if (credentials != null && !credentials.isEmpty()) {
            options.put("allowCredentials", descriptors(credentials));
        }
        return options;
    }

    /**
     * 认证器选择条件。
     *
     * @param forRegistration true 表示注册场景
     * @return 认证器选择条件
     */
    private Map<String, Object> authenticatorSelection(boolean forRegistration) {
        Map<String, Object> selection = new LinkedHashMap<>();
        boolean residentKey = forRegistration && properties.isAllowUsernameless();
        selection.put("residentKey", residentKey ? RESIDENT_KEY_PREFERRED : RESIDENT_KEY_DISCOURAGED);
        selection.put("requireResidentKey", false);
        selection.put("userVerification", userVerification());
        return selection;
    }

    /**
     * 用户验证要求。
     *
     * @return {@code required} 或 {@code preferred}
     */
    private String userVerification() {
        return properties.isUserVerificationRequired() ? "required" : "preferred";
    }

    /**
     * 支持的签名算法列表。
     *
     * @return 算法参数列表
     */
    private static List<Map<String, Object>> publicKeyCredentialParams() {
        List<Map<String, Object>> params = new ArrayList<>(2);
        params.add(algEntry(ALG_ES256));
        params.add(algEntry(ALG_RS256));
        return params;
    }

    /**
     * 单个算法参数。
     *
     * @param alg COSE 算法标识
     * @return 算法参数
     */
    private static Map<String, Object> algEntry(int alg) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", TYPE_PUBLIC_KEY);
        entry.put("alg", alg);
        return entry;
    }

    /**
     * 凭证描述符列表。
     *
     * @param credentials 凭证实体列表
     * @return 描述符列表
     */
    private static List<Map<String, Object>> descriptors(List<WebAuthnCredential> credentials) {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        if (credentials == null) {
            return descriptors;
        }
        for (WebAuthnCredential credential : credentials) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("type", TYPE_PUBLIC_KEY);
            descriptor.put("id", credential.getCredentialId());
            List<String> transports = splitTransports(credential.getTransports());
            if (!transports.isEmpty()) {
                descriptor.put("transports", transports);
            }
            descriptors.add(descriptor);
        }
        return descriptors;
    }

    /**
     * 拆分逗号分隔的传输方式。
     *
     * @param transports 逗号分隔串
     * @return 传输方式列表;为空时返回空列表
     */
    static List<String> splitTransports(String transports) {
        if (transports == null || transports.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String piece : transports.split(",")) {
            if (!piece.isBlank()) {
                result.add(piece.trim());
            }
        }
        return result;
    }
}
