package club.beenest.iam.authz.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Passkey 基础设施装配。
 * <p>{@link ObjectConverter} 单独暴露成 bean:CBOR 编解码器内部缓存了解析器与序列化器,
 * 每次校验都新建一个既浪费又容易在多线程下反复初始化;凭证数据的序列化与
 * 验签共用同一份,格式口径才不会分叉。</p>
 * <p>{@link WebAuthnManager} 用非严格模式:平台不校验 attestation 证书链到受信根,
 * 只验证断言签名与挑战。做设备型号白名单的部署可换成严格模式并配信任锚。</p>
 */
@Configuration
@EnableConfigurationProperties(WebAuthnProperties.class)
public class WebAuthnConfiguration {

    /**
     * CBOR/JSON 编解码器。
     *
     * @return 供凭证数据序列化与验签共用的转换器
     */
    @Bean
    public ObjectConverter webAuthnObjectConverter() {
        return new ObjectConverter();
    }

    /**
     * attested credential data 转换器(凭证数据落库与读回)。
     *
     * @param objectConverter 编解码器
     * @return 转换器
     */
    @Bean
    public AttestedCredentialDataConverter attestedCredentialDataConverter(ObjectConverter objectConverter) {
        return new AttestedCredentialDataConverter(objectConverter);
    }

    /**
     * WebAuthn 协议校验器。
     *
     * @param objectConverter 编解码器
     * @return 非严格模式校验器
     */
    @Bean
    public WebAuthnManager webAuthnManager(ObjectConverter objectConverter) {
        return WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);
    }
}
