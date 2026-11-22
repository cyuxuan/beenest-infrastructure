package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.SamlServiceProviderRequest;
import club.beenest.iam.admin.dto.SamlServiceProviderResponse;
import club.beenest.iam.admin.service.SamlServiceProviderService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.saml.SamlIdpEndpoints;
import club.beenest.iam.authz.saml.SamlIdpProtocol;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.spi.provider.SamlServiceProviderStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SAML 服务方登记管理实现。
 * <p>实体 ID 与别名在域内唯一;强制验签时必须同时提供证书,否则到登录那一刻才发现
 * 配置不全,排查成本高得多。</p>
 */
@Service
public class SamlServiceProviderServiceImpl implements SamlServiceProviderService {

    /** 未指定时的 NameID 格式 */
    private static final String DEFAULT_NAME_ID_FORMAT = "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified";

    private final SamlServiceProviderStore store;

    public SamlServiceProviderServiceImpl(SamlServiceProviderStore store) {
        this.store = store;
    }

    @Override
    public List<SamlServiceProviderResponse> list() {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return store.listByRealm(realmId).stream().map(SamlServiceProviderServiceImpl::toResponse).toList();
    }

    @Override
    public SamlServiceProviderResponse get(Long id) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        return toResponse(requireProvider(realmId, id));
    }

    @Override
    public SamlServiceProviderResponse create(SamlServiceProviderRequest request) {
        var principal = CurrentUserHolder.require();
        Long realmId = principal.getRealmId();
        requireUnique(realmId, request, null);
        Long id = store.save(toModel(null, realmId, request));
        return toResponse(requireProvider(realmId, id));
    }

    @Override
    public SamlServiceProviderResponse update(Long id, SamlServiceProviderRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        requireProvider(realmId, id);
        requireUnique(realmId, request, id);
        store.save(toModel(id, realmId, request));
        return toResponse(requireProvider(realmId, id));
    }

    @Override
    public void delete(Long id) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        requireProvider(realmId, id);
        store.delete(realmId, id);
    }

    @Override
    public Map<String, String> endpoints(String origin) {
        String realmKey = CurrentUserHolder.require().getRealmKey();
        SamlIdpEndpoints endpoints = SamlIdpEndpoints.of(origin, realmKey);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("entityId", endpoints.entityId());
        values.put("ssoUrl", endpoints.ssoUrl());
        values.put("sloUrl", endpoints.sloUrl());
        values.put("metadataUrl", endpoints.entityId());
        return values;
    }

    /**
     * 取登记项,不存在即报错。
     *
     * @param realmId 所属身份域
     * @param id      登记主键
     * @return 登记模型
     * @throws BusinessException 登记不存在或不属于该域
     */
    private SamlServiceProviderModel requireProvider(Long realmId, Long id) {
        return store.findById(realmId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SAML 服务方登记不存在"));
    }

    /**
     * 校验别名与实体 ID 在域内唯一。
     *
     * @param realmId    所属身份域
     * @param request    登记请求
     * @param excludeId  修改时排除自身;新增传 null
     * @throws BusinessException 别名或实体 ID 已被占用
     */
    private void requireUnique(Long realmId, SamlServiceProviderRequest request, Long excludeId) {
        Optional<SamlServiceProviderModel> sameKey = store.findByKey(realmId, request.spKey());
        if (sameKey.isPresent() && !sameKey.get().id().equals(excludeId)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "别名已被占用: " + request.spKey());
        }
        Optional<SamlServiceProviderModel> sameEntity = store.findByEntityId(realmId, request.entityId());
        if (sameEntity.isPresent() && !sameEntity.get().id().equals(excludeId)) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "SP 实体 ID 已被占用: " + request.entityId());
        }
    }

    /**
     * 请求转模型。
     *
     * @param id      登记主键;新增传 null
     * @param realmId 所属身份域
     * @param request 登记请求
     * @return 登记模型
     * @throws BusinessException 强制验签但未提供证书,或证书无法解析
     */
    private static SamlServiceProviderModel toModel(Long id, Long realmId, SamlServiceProviderRequest request) {
        String certificate = trimToEmpty(request.spCertificate());
        if (Boolean.TRUE.equals(request.requireSignedAuthnRequest()) && certificate.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "要求签名请求时必须提供 SP 签名证书");
        }
        if (!certificate.isBlank()) {
            // 现场解析一次:证书格式错误在这里就暴露,而不是等到登录时
            SamlIdpProtocol.certificateFromPem(certificate);
        }
        return new SamlServiceProviderModel(id, realmId, request.spKey().trim(), request.entityId().trim(),
                trimToEmpty(request.displayName()), request.acsUrl().trim(), trimToEmpty(request.sloUrl()),
                defaultIfBlank(request.nameIdFormat(), DEFAULT_NAME_ID_FORMAT),
                Boolean.TRUE.equals(request.signAssertion()),
                Boolean.TRUE.equals(request.requireSignedAuthnRequest()),
                certificate,
                request.attributeMapping() == null ? Map.of() : Map.copyOf(request.attributeMapping()),
                Boolean.TRUE.equals(request.enabled()));
    }

    /**
     * 模型转响应。
     *
     * @param provider 登记模型
     * @return 响应
     */
    private static SamlServiceProviderResponse toResponse(SamlServiceProviderModel provider) {
        return new SamlServiceProviderResponse(provider.id(), provider.spKey(), provider.entityId(),
                provider.displayName(), provider.acsUrl(), provider.sloUrl(), provider.nameIdFormat(),
                provider.signAssertion(), provider.requireSignedAuthnRequest(), provider.hasCertificate(),
                provider.spCertificate(), provider.attributeMapping(), provider.enabled());
    }

    /**
     * 去除首尾空白,空值转为空串。
     *
     * @param value 原值
     * @return 规整后的字符串
     */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 空白时取默认值。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 非空原值或默认值
     */
    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
