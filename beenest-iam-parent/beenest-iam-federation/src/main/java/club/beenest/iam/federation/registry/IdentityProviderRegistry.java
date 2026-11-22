package club.beenest.iam.federation.registry;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderLoginMode;
import club.beenest.iam.core.spi.federation.ProviderTypeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份提供方注册表。
 * <p>收集容器内全部工厂实现,按提供方类型(providerId)索引;
 * 新增三方登录只需新增一个 Provider 模块并暴露为 Spring Bean。</p>
 * <p>内置工厂与外置扩展包走同一条注册路径:外置扩展包在启动时被装载器注册进来,
 * 之后的行为与内置实现完全一致。</p>
 * <p>凭据类提供方还可以声明一个自定义授权类型:平台的标准令牌端点据此把
 * 该授权类型分派回它自己,扩展包因此不必改动平台代码即可接入新登录方式。</p>
 */
@Component
public class IdentityProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(IdentityProviderRegistry.class);

    /**
     * 标准授权类型:扩展包不得占用。
     * <p>自定义授权类型与标准类型重名会让扩展包接管平台的授权码、刷新令牌等标准流程,
     * 注册时直接拒绝。</p>
     */
    private static final Set<String> STANDARD_GRANT_TYPES = Set.of(
            "authorization_code",
            "client_credentials",
            "refresh_token",
            "password",
            "implicit",
            "urn:ietf:params:oauth:grant-type:device_code",
            "urn:ietf:params:oauth:grant-type:token-exchange",
            "urn:ietf:params:oauth:grant-type:jwt-bearer");

    /** 提供方工厂:按类型索引。允许运行时注册,故用并发容器。 */
    private final Map<String, IdentityProviderFactory<?>> factoriesByProviderId = new ConcurrentHashMap<>();

    /** 自定义授权类型 → 工厂:令牌端点按授权类型分派用。 */
    private final Map<String, IdentityProviderFactory<?>> factoriesByGrantType = new ConcurrentHashMap<>();

    /**
     * 注入容器内全部提供方工厂并逐个注册。
     *
     * @param factories 内置工厂列表(含已声明为 Bean 的扩展)
     */
    public IdentityProviderRegistry(List<IdentityProviderFactory<?>> factories) {
        factories.forEach(this::register);
    }

    /**
     * 注册一个提供方工厂。
     * <p>同一类型重复注册时后者生效并留下告警,便于确认替换是否落地;
     * 自定义授权类型与标准类型重名时拒绝注册。</p>
     *
     * @param factory 提供方工厂
     * @throws IllegalStateException 工厂声明的自定义授权类型与标准类型重名
     */
    public synchronized void register(IdentityProviderFactory<?> factory) {
        String providerId = factory.getProviderId();
        // 同名校验在写入映射之前:被拒绝的注册不能顶掉已有工厂
        String grantType = factory.getGrantType();
        if (grantType != null && !grantType.isBlank() && STANDARD_GRANT_TYPES.contains(grantType)) {
            throw new IllegalStateException("身份提供方 " + providerId
                    + " 声明的自定义授权类型与标准类型重名,已拒绝注册: " + grantType);
        }
        IdentityProviderFactory<?> existing = factoriesByProviderId.get(providerId);
        if (existing != null) {
            log.warn("身份提供方类型重复注册,后者生效: providerId={}, 原有实现={}, 新实现={}",
                    providerId, existing.getClass().getName(), factory.getClass().getName());
        }
        factoriesByProviderId.put(providerId, factory);
        if (grantType != null && !grantType.isBlank()) {
            factoriesByGrantType.put(grantType, factory);
        }
    }

    /**
     * 按提供方类型取工厂。
     *
     * @param providerId 提供方类型标识
     * @return 对应工厂;未注册时为空
     */
    public Optional<IdentityProviderFactory<?>> getFactory(String providerId) {
        return Optional.ofNullable(factoriesByProviderId.get(providerId));
    }

    /**
     * 按自定义授权类型取工厂,令牌端点据此分派。
     *
     * @param grantType 授权类型标识
     * @return 对应工厂;未注册或入参为空时为空
     */
    public Optional<IdentityProviderFactory<?>> findByGrantType(String grantType) {
        if (grantType == null || grantType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(factoriesByGrantType.get(grantType));
    }

    /**
     * 列出容器内已注册的全部提供方类型。
     * <p>按类型标识排序,保证管理端展示顺序稳定;类型信息由各工厂自报,
     * 新增提供方不需要在核心或管理端登记。</p>
     *
     * @return 提供方类型信息列表
     */
    public List<ProviderTypeInfo> listProviderTypes() {
        return factoriesByProviderId.values().stream()
                .map(factory -> new ProviderTypeInfo(
                        factory.getProviderId(), factory.getName(),
                        factory.getLoginModes(), factory.getGrantType(),
                        factory.getConfigProperties()))
                .sorted(Comparator.comparing(ProviderTypeInfo::providerId))
                .toList();
    }

    /**
     * 该提供方类型是否支持浏览器跳转登录。
     *
     * @param providerId 提供方类型标识
     * @return 支持跳转登录返回 true;未注册或不支持返回 false
     */
    public boolean supportsRedirect(String providerId) {
        return getFactory(providerId)
                .map(factory -> factory.getLoginModes().contains(ProviderLoginMode.REDIRECT))
                .orElse(false);
    }

    /**
     * 按完整配置创建 Provider 实例。
     *
     * @param config 提供方运行配置
     * @return Provider 实例
     * @throws BusinessException 提供方类型未注册(报 NOT_FOUND,与平台故障区分)
     */
    public IdentityProvider<?> create(IdentityProviderConfig config) {
        IdentityProviderFactory<?> factory = factoriesByProviderId.get(config.providerId());
        if (factory == null) {
            // 未注册的类型按"没有这个入口"处理
            throw new BusinessException(ErrorCode.NOT_FOUND, "未接入的身份提供方类型: " + config.providerId());
        }
        return factory.create(config);
    }
}
