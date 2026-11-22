package club.beenest.iam.federation.broker;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.security.IamIdpConfigKeys;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 三方登录经纪流程:发起跳转与回调落地。
 * <p>流程:解析身份域 → 读取提供方配置 → 经注册表创建 Provider 实例 →
 * 拼授权地址 / 换外部身份 → 绑定服务 find-or-create 本地用户。</p>
 * <p>配置里的机密值(客户端密钥)加密落库,本类在用之前解密;
 * 外部 IdP 抛出的运行时异常统一转成 {@link ErrorCode#IDP_UNAVAILABLE},
 * {@link BusinessException} 等平台侧业务异常原样透出,两类故障的处置归属不同。</p>
 */
@Component
public class IdentityBrokerService {

    private static final Logger log = LoggerFactory.getLogger(IdentityBrokerService.class);

    private final RealmProvider realmProvider;
    private final IdentityProviderStore identityProviderStore;
    private final IdentityProviderRegistry registry;
    private final UserFederationLinkService federationLinkService;
    private final IamSecretCrypto secretCrypto;

    public IdentityBrokerService(RealmProvider realmProvider,
                                  IdentityProviderStore identityProviderStore,
                                  IdentityProviderRegistry registry,
                                  UserFederationLinkService federationLinkService,
                                  IamSecretCrypto secretCrypto) {
        this.realmProvider = realmProvider;
        this.identityProviderStore = identityProviderStore;
        this.registry = registry;
        this.federationLinkService = federationLinkService;
        this.secretCrypto = secretCrypto;
    }

    /**
     * 发起三方登录:返回外部提供方的授权跳转地址。
     *
     * @param realmKey   身份域标识
     * @param alias      提供方别名(iam_identity_provider.alias)
     * @param redirectUri 本 IAM 的 broker 回调地址
     * @param state      防伪状态,原样回传
     * @return 外部授权跳转地址
     */
    public String buildAuthorizationUrl(String realmKey, String alias, String redirectUri, String state) {
        // 1. 读取提供方并创建实例
        IdentityProviderModel model = loadIdp(realmKey, alias);
        IdentityProvider<?> provider = registry.create(toConfig(model));
        // 2. 拼装授权跳转地址
        try {
            return provider.authorizationUrl(new AuthorizationRequest(redirectUri, state, Map.of()));
        } catch (BusinessException e) {
            // 配置类业务异常原样抛出,不包装为上游不可用
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(alias, "生成授权地址失败", e);
        }
    }

    /**
     * 三方登录落地:按提供方自己声明的形态换取外部身份,再绑定/复用本地用户。
     * <p>联邦运行时的唯一登录入口:跳转类与凭据类的差别落在
     * {@link CallbackContext} 携带的内容与提供方实现内部,联邦运行时不按形态分支。</p>
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @param ctx      凭据上下文(授权码、状态与提供方自定的凭据)
     * @return 本地用户
     */
    public UserModel completeLogin(String realmKey, String alias, CallbackContext ctx) {
        // 1. 读取提供方并创建实例
        IdentityProviderModel model = loadIdp(realmKey, alias);
        IdentityProvider<?> provider = registry.create(toConfig(model));
        // 2. 换外部身份
        BrokeredIdentityContext identity;
        try {
            identity = provider.buildIdentityFromCallback(ctx);
        } catch (BusinessException e) {
            // 配置类业务异常原样抛出,不包装为上游不可用
            throw e;
        } catch (RuntimeException e) {
            // 外部 IdP 的网络故障、端点报错、响应非法都归到同一类:上游不可用
            throw unavailable(alias, "换取外部身份失败", e);
        }
        // 3. 绑定/复用本地用户
        return federationLinkService.findOrCreateLinkedUser(identity, model.realmId(), model.id());
    }

    /**
     * 服务方(SP)元数据:只有需要向外部 IdP 登记自身的提供方(SAML)提供。
     * <p>与企业 IdP 对接时,管理员在 IdP 侧导入该 XML 完成登记,
     * 替代手工填写 ACS 地址等字段。</p>
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @param acsUrl   本平台的断言消费地址
     * @return 元数据 XML;该提供方类型不支持时为空
     */
    public Optional<String> serviceProviderMetadata(String realmKey, String alias, String acsUrl) {
        IdentityProviderModel model = loadIdp(realmKey, alias);
        IdentityProvider<?> provider = registry.create(toConfig(model));
        try {
            return provider.serviceProviderMetadata(acsUrl);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(alias, "生成服务方元数据失败", e);
        }
    }

    /**
     * 按域与别名读取启用的提供方;不存在或未启用视为配置错误。
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @return 启用中的提供方配置模型
     * @throws BusinessException 域不存在、提供方不存在或已停用
     */
    private IdentityProviderModel loadIdp(String realmKey, String alias) {
        Long realmId = realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey))
                .id();
        IdentityProviderModel model = identityProviderStore.findByIdp(realmId, alias)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份提供方不存在: " + alias));
        if (!model.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "身份提供方已停用: " + alias);
        }
        return model;
    }

    /**
     * 领域模型 → Provider 配置。
     * <p>机密项在此解密:落库时是密文,Provider 只认明文。解密作用域取"域 + 别名",
     * 与写入侧保持一致。</p>
     *
     * @param model 提供方配置模型
     * @return Provider 运行配置(机密项已解密)
     */
    private IdentityProviderConfig toConfig(IdentityProviderModel model) {
        Map<String, String> plain = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : model.config().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (IamIdpConfigKeys.isSensitive(key) && value != null) {
                value = secretCrypto.decrypt(IamIdpConfigKeys.PURPOSE, scopeOf(model), value);
            }
            plain.put(key, value);
        }
        return new IdentityProviderConfig(
                model.alias(), model.providerId(), model.displayName(), model.enabled(), plain);
    }

    /**
     * 机密密文的绑定作用域:域 + 别名,写入与读取两侧共用。
     *
     * @param model 提供方配置模型
     * @return 形如 {@code realmId:alias} 的作用域串
     */
    public static String scopeOf(IdentityProviderModel model) {
        return model.realmId() + ":" + model.alias();
    }

    /**
     * 把提供方运行时异常包装为上游不可用,并记录原始原因。
     *
     * @param alias  提供方别名
     * @param reason 失败环节描述
     * @param cause  原始异常
     * @return 待抛出的业务异常(IDP_UNAVAILABLE)
     */
    private static BusinessException unavailable(String alias, String reason, RuntimeException cause) {
        log.warn("身份提供方调用失败: alias={}, reason={}, error={}", alias, reason, cause.getMessage());
        return new BusinessException(ErrorCode.IDP_UNAVAILABLE,
                "身份提供方(" + alias + ")" + reason + ",请改用账号密码登录");
    }
}
