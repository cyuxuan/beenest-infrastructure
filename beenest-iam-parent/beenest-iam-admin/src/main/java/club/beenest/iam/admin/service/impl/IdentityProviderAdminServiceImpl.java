package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.IdpMapperCreateRequest;
import club.beenest.iam.admin.dto.IdpMapperResponse;
import club.beenest.iam.admin.dto.IdentityProviderCreateRequest;
import club.beenest.iam.admin.dto.IdentityProviderResponse;
import club.beenest.iam.admin.dto.IdentityProviderUpdateRequest;
import club.beenest.iam.admin.service.IdentityProviderAdminService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.security.IamIdpConfigKeys;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderTypeInfo;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import club.beenest.iam.persistence.entity.IdentityProvider;
import club.beenest.iam.persistence.entity.IdentityProviderMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 身份提供方管理实现。
 * <p>配置中的敏感值加密落库,响应只返回配置键名;编辑时机密项留空表示保留原值,
 * 非机密项整体覆盖。</p>
 */
@Service
public class IdentityProviderAdminServiceImpl implements IdentityProviderAdminService {

    /** iam_identity_provider 表 Mapper(与实体 IdentityProviderMapper 简名冲突,用全限定名) */
    private final club.beenest.iam.persistence.mapper.IdentityProviderMapper identityProviderMapper;
    /** iam_identity_provider_mapper 表 Mapper */
    private final club.beenest.iam.persistence.mapper.IdentityProviderMapperMapper idpMapperMapper;
    /** 联邦存储:删除提供方时解绑外部身份 */
    private final IdentityProviderStore identityProviderStore;
    /** 提供方类型注册表:给出各类型的必填配置项声明 */
    private final IdentityProviderRegistry identityProviderRegistry;
    /** 机密加密:客户端密钥以密文落库 */
    private final IamSecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;

    public IdentityProviderAdminServiceImpl(
            club.beenest.iam.persistence.mapper.IdentityProviderMapper identityProviderMapper,
            club.beenest.iam.persistence.mapper.IdentityProviderMapperMapper idpMapperMapper,
            IdentityProviderStore identityProviderStore,
            IdentityProviderRegistry identityProviderRegistry,
            IamSecretCrypto secretCrypto,
            ObjectMapper objectMapper) {
        this.identityProviderMapper = identityProviderMapper;
        this.idpMapperMapper = idpMapperMapper;
        this.identityProviderStore = identityProviderStore;
        this.identityProviderRegistry = identityProviderRegistry;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<IdentityProviderResponse> list() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return identityProviderMapper.selectByRealmId(realmId).stream()
                .map(this::toResponse).toList();
    }

    @Override
    public IdentityProviderResponse get(Long id) {
        return toResponse(requireInRealm(id));
    }

    @Override
    public IdentityProviderResponse create(IdentityProviderCreateRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        if (identityProviderMapper.selectByRealmIdAndAlias(realmId, request.alias()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "提供方别名已存在: " + request.alias());
        }
        boolean enabled = request.enabled() == null || request.enabled();
        Map<String, String> config = request.config() == null ? Map.of() : request.config();
        // 1. 启用状态必须配齐必填项
        if (enabled) {
            validateRequiredConfig(request.providerId(), config);
        }
        IdentityProvider idp = new IdentityProvider();
        idp.setRealmId(realmId);
        idp.setAlias(request.alias());
        idp.setProviderId(request.providerId());
        idp.setDisplayName(request.displayName());
        idp.setEnabled(enabled);
        idp.setConfig(writeConfig(config, scope(realmId, request.alias())));
        identityProviderMapper.insert(idp);
        return toResponse(identityProviderMapper.selectById(idp.getId()));
    }

    @Override
    public IdentityProviderResponse update(Long id, IdentityProviderUpdateRequest request) {
        IdentityProvider idp = requireInRealm(id);
        if (request.displayName() != null) {
            idp.setDisplayName(request.displayName());
        }
        boolean enabled = request.enabled() == null || Boolean.TRUE.equals(idp.getEnabled());
        if (request.enabled() != null) {
            idp.setEnabled(request.enabled());
        }
        if (request.config() != null) {
            Map<String, String> merged = mergeConfig(idp, request.config());
            if (enabled) {
                validateRequiredConfig(idp.getProviderId(), merged);
            }
            idp.setConfig(writeConfig(merged, scope(idp.getRealmId(), idp.getAlias())));
        } else if (enabled) {
            // 本次没改配置也按当前配置判定
            validateRequiredConfig(idp.getProviderId(), parseConfig(idp.getConfig()));
        }
        identityProviderMapper.updateById(idp);
        return toResponse(identityProviderMapper.selectById(id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireInRealm(id);
        // 1. 解绑外部身份
        identityProviderStore.unlinkByIdp(id);
        // 2. 删掉映射规则与提供方本身
        idpMapperMapper.softDeleteByIdpId(id);
        identityProviderMapper.softDeleteById(id);
    }

    @Override
    public List<IdpMapperResponse> listMappers(Long idpId) {
        requireInRealm(idpId);
        return idpMapperMapper.selectByIdpId(idpId).stream().map(this::toMapperResponse).toList();
    }

    @Override
    public IdpMapperResponse createMapper(Long idpId, IdpMapperCreateRequest request) {
        IdentityProvider idp = requireInRealm(idpId);
        IdentityProviderMapper mapper = new IdentityProviderMapper();
        mapper.setRealmId(idp.getRealmId());
        mapper.setIdpId(idpId);
        mapper.setMapperType(request.mapperType() == null || request.mapperType().isBlank()
                ? "ATTRIBUTE" : request.mapperType());
        mapper.setSourceAttribute(request.sourceAttribute());
        mapper.setTargetAttribute(request.targetAttribute());
        idpMapperMapper.insert(mapper);
        return toMapperResponse(idpMapperMapper.selectById(mapper.getId()));
    }

    @Override
    public void deleteMapper(Long idpId, Long mapperId) {
        requireInRealm(idpId);
        IdentityProviderMapper mapper = idpMapperMapper.selectById(mapperId);
        if (mapper == null || !mapper.getIdpId().equals(idpId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "属性映射器不存在: " + mapperId);
        }
        idpMapperMapper.softDeleteById(mapperId);
    }

    // ===== 内部实现 =====

    /**
     * 读取属于当前域的身份提供方。
     *
     * @param id 身份提供方主键
     * @return 身份提供方
     * @throws BusinessException 身份提供方不存在或不属于当前域
     */
    private IdentityProvider requireInRealm(Long id) {
        IdentityProvider idp = identityProviderMapper.selectById(id);
        if (idp == null || !CurrentUserHolder.require().getRealmId().equals(idp.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "身份提供方不存在: " + id);
        }
        return idp;
    }

    /**
     * 校验启用状态的提供方是否配齐必填配置项。
     * <p>类型尚未接入注册表时跳过校验,错误信息按项列出缺失的配置。</p>
     *
     * @param providerId 提供方类型标识
     * @param config     提供方配置
     * @throws BusinessException 存在未配置的必填项
     */
    private void validateRequiredConfig(String providerId, Map<String, String> config) {
        ProviderTypeInfo type = identityProviderRegistry.listProviderTypes().stream()
                .filter(item -> item.providerId().equals(providerId))
                .findFirst()
                .orElse(null);
        if (type == null) {
            return;
        }
        List<String> missing = type.configProperties().stream()
                .filter(ProviderConfigProperty::required)
                .filter(property -> {
                    String value = config == null ? null : config.get(property.name());
                    return value == null || value.isBlank();
                })
                .map(property -> property.name() + "(" + property.label() + ")")
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "启用该提供方前必须配齐以下配置项: " + String.join("、", missing));
        }
    }

    /**
     * 提交的配置与库中原配置合并,本次留空的机密项沿用库中原值。
     *
     * @param idp       库中身份提供方
     * @param submitted 提交的配置
     * @return 合并后的配置
     */
    private Map<String, String> mergeConfig(IdentityProvider idp, Map<String, String> submitted) {
        Map<String, String> merged = new TreeMap<>(submitted == null ? Map.of() : submitted);
        Map<String, String> stored = parseConfig(idp.getConfig());
        for (String key : sensitiveKeys(stored)) {
            boolean submittedBlank = merged.get(key) == null || merged.get(key).isBlank();
            if (submittedBlank && stored.get(key) != null && !stored.get(key).isBlank()) {
                merged.put(key, stored.get(key));
            }
        }
        return merged;
    }

    /**
     * 取库中已存的机密配置键,按机密规则逐键判定。
     *
     * @param stored 已存配置
     * @return 机密配置键
     */
    private static List<String> sensitiveKeys(Map<String, String> stored) {
        return stored.keySet().stream()
                .filter(IamIdpConfigKeys::isSensitive)
                .toList();
    }

    /**
     * 机密密文的绑定作用域,由身份域与别名组成。
     *
     * @param realmId 身份域主键
     * @param alias   提供方别名
     * @return 加密作用域
     */
    private static String scope(Long realmId, String alias) {
        return realmId + ":" + alias;
    }

    /**
     * 配置序列化,机密项加密后落库。
     *
     * @param config 提供方配置
     * @param scope  加密作用域
     * @return 配置 JSON
     * @throws BusinessException 序列化失败
     */
    private String writeConfig(Map<String, String> config, String scope) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        Map<String, String> secured = new TreeMap<>();
        config.forEach((key, value) -> {
            if (value == null) {
                secured.put(key, null);
                return;
            }
            if (IamIdpConfigKeys.isSensitive(key)) {
                // 已是密文则不重复加密:合并时可能把库中原值带了回来
                secured.put(key, secretCrypto.isEncrypted(value)
                        ? value : secretCrypto.encrypt(IamIdpConfigKeys.PURPOSE, scope, value));
            } else {
                secured.put(key, value);
            }
        });
        try {
            return objectMapper.writeValueAsString(secured);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "提供方配置序列化失败");
        }
    }

    /**
     * 取配置 JSON 的键名,按字典序排列。
     *
     * @param json 配置 JSON
     * @return 配置键名
     */
    private List<String> configKeys(String json) {
        Map<String, String> map = parseConfig(json);
        return map.keySet().stream().sorted().toList();
    }

    /**
     * 解析配置 JSON,空值或解析失败时返回空映射。
     *
     * @param json 配置 JSON
     * @return 配置键值对
     */
    private Map<String, String> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<TreeMap<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 身份提供方转响应,只携带配置键名。
     *
     * @param idp 身份提供方
     * @return 身份提供方响应
     */
    private IdentityProviderResponse toResponse(IdentityProvider idp) {
        return new IdentityProviderResponse(idp.getId(), idp.getAlias(), idp.getProviderId(),
                idp.getDisplayName(), idp.getEnabled(), configKeys(idp.getConfig()), idp.getCreatedTime());
    }

    /**
     * 属性映射器转响应。
     *
     * @param mapper 属性映射器
     * @return 属性映射器响应
     */
    private IdpMapperResponse toMapperResponse(IdentityProviderMapper mapper) {
        return new IdpMapperResponse(mapper.getId(), mapper.getMapperType(),
                mapper.getSourceAttribute(), mapper.getTargetAttribute(), mapper.getCreatedTime());
    }
}
