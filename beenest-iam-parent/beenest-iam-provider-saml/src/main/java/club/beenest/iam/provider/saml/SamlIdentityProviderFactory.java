package club.beenest.iam.provider.saml;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SAML 2.0 提供方工厂。
 *
 * <p>配置项随本类声明,控制台的提供方表单按声明动态渲染,管理端没有 SAML 专用页面。
 * 证书以 TEXT 形态录入(整段 PEM)而非密码类型:X.509 证书是公开信息,
 * 回显便于核对指纹。</p>
 */
@Component
public class SamlIdentityProviderFactory implements IdentityProviderFactory<SamlIdentityProvider> {

    @Override
    public String getProviderId() {
        return "saml";
    }

    @Override
    public String getName() {
        return "SAML 2.0";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty(SamlIdentityProvider.KEY_IDP_ENTITY_ID,
                        "IdP 实体 ID", ConfigType.STRING, true, false, null,
                        "外部 IdP 的 EntityID,如 https://adfs.example.com/adfs/services/trust"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_SSO_URL,
                        "IdP 单点登录地址", ConfigType.URL, true, false, null,
                        "外部 IdP 的 SSO 端点(HTTP-Redirect 绑定),如 https://adfs.example.com/adfs/ls/"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_IDP_CERTIFICATE,
                        "IdP 签名证书", ConfigType.TEXT, true, false, null,
                        "外部 IdP 的 X.509 证书(PEM,含或不含 BEGIN/END 头均可),用于校验断言签名"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_SP_ENTITY_ID,
                        "本平台实体 ID", ConfigType.STRING, false, false, null,
                        "留空时按回调地址推导;IdP 侧登记的值需与这里一致"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_NAME_ID_FORMAT,
                        "NameID 格式", ConfigType.STRING, false, false,
                        "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified",
                        "请求的 NameID 格式;不确定时保持默认"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_USERNAME_ATTRIBUTE,
                        "用户名属性", ConfigType.STRING, false, false, null,
                        "取用户名的断言属性名,可用逗号分隔多个作为候选;留空时按常见命名自动匹配"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_EMAIL_ATTRIBUTE,
                        "邮箱属性", ConfigType.STRING, false, false, null,
                        "取邮箱的断言属性名,可用逗号分隔多个作为候选"),
                new ProviderConfigProperty(SamlIdentityProvider.KEY_NICKNAME_ATTRIBUTE,
                        "昵称属性", ConfigType.STRING, false, false, null,
                        "取昵称的断言属性名,可用逗号分隔多个作为候选")
        );
    }

    @Override
    public SamlIdentityProvider create(IdentityProviderConfig config) {
        return new SamlIdentityProvider(config);
    }
}
