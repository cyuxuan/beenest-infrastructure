package club.beenest.iam.persistence.manager;

import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.spi.provider.SamlServiceProviderStore;
import club.beenest.iam.persistence.entity.SamlServiceProvider;
import club.beenest.iam.persistence.mapper.SamlServiceProviderMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SAML 服务方登记存储(iam_saml_service_provider)。
 * <p>属性映射以 JSONB 存放,读写时在 {@code Map} 与 JSON 文本之间转换;
 * JSON 损坏时按空映射处理,不让单行坏数据拖垮整条登录链路。</p>
 */
@Component
public class IamSamlServiceProviderStore implements SamlServiceProviderStore {

    private final SamlServiceProviderMapper samlServiceProviderMapper;
    private final ObjectMapper objectMapper;

    public IamSamlServiceProviderStore(SamlServiceProviderMapper samlServiceProviderMapper,
                                       ObjectMapper objectMapper) {
        this.samlServiceProviderMapper = samlServiceProviderMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 SP 实体 ID 查找登记。
     *
     * @param realmId  所属身份域
     * @param entityId SP 实体 ID
     * @return 登记;不存在或已删除时为空
     */
    @Override
    public Optional<SamlServiceProviderModel> findByEntityId(Long realmId, String entityId) {
        return Optional.ofNullable(samlServiceProviderMapper.selectByEntityId(realmId, entityId))
                .map(this::toModel);
    }

    /**
     * 按站内别名查找登记。
     *
     * @param realmId 所属身份域
     * @param spKey   站内别名
     * @return 登记;不存在或已删除时为空
     */
    @Override
    public Optional<SamlServiceProviderModel> findByKey(Long realmId, String spKey) {
        return Optional.ofNullable(samlServiceProviderMapper.selectByKey(realmId, spKey))
                .map(this::toModel);
    }

    /**
     * 按主键查找登记。
     *
     * @param realmId 所属身份域,用于限定查找范围
     * @param id      登记主键
     * @return 登记;不存在、已删除或不属于该域时为空
     */
    @Override
    public Optional<SamlServiceProviderModel> findById(Long realmId, Long id) {
        SamlServiceProvider entity = samlServiceProviderMapper.selectById(id);
        if (entity == null || !realmId.equals(entity.getRealmId())) {
            return Optional.empty();
        }
        return Optional.of(toModel(entity));
    }

    /**
     * 列出域内全部登记。
     *
     * @param realmId 所属身份域
     * @return 登记列表;无记录返回空列表
     */
    @Override
    public List<SamlServiceProviderModel> listByRealm(Long realmId) {
        return samlServiceProviderMapper.selectByRealmId(realmId).stream()
                .map(this::toModel)
                .toList();
    }

    /**
     * 保存登记:主键为空时插入,否则按主键更新。
     *
     * @param model 登记模型
     * @return 保存后的主键
     */
    @Override
    public Long save(SamlServiceProviderModel model) {
        SamlServiceProvider entity = toEntity(model);
        if (entity.getId() == null) {
            samlServiceProviderMapper.insert(entity);
            return entity.getId();
        }
        samlServiceProviderMapper.updateById(entity);
        return entity.getId();
    }

    /**
     * 逻辑删除登记。
     *
     * @param realmId 所属身份域,用于限定删除范围
     * @param id      登记主键
     * @return 受影响行数;登记不存在或不属于该域时为 0
     */
    @Override
    public int delete(Long realmId, Long id) {
        return samlServiceProviderMapper.softDelete(realmId, id);
    }

    /**
     * 实体转领域模型。
     *
     * @param entity 登记实体
     * @return 登记模型
     */
    private SamlServiceProviderModel toModel(SamlServiceProvider entity) {
        return new SamlServiceProviderModel(
                entity.getId(), entity.getRealmId(), entity.getSpKey(), entity.getEntityId(),
                entity.getDisplayName(), entity.getAcsUrl(), entity.getSloUrl(), entity.getNameIdFormat(),
                Boolean.TRUE.equals(entity.getSignAssertion()),
                Boolean.TRUE.equals(entity.getRequireSignedAuthnRequest()),
                entity.getSpCertificate(), parseMapping(entity.getAttributeMapping()),
                Boolean.TRUE.equals(entity.getEnabled()));
    }

    /**
     * 领域模型转实体;属性映射序列化为 JSON 文本。
     *
     * @param model 登记模型
     * @return 登记实体
     */
    private SamlServiceProvider toEntity(SamlServiceProviderModel model) {
        SamlServiceProvider entity = new SamlServiceProvider();
        entity.setId(model.id());
        entity.setRealmId(model.realmId());
        entity.setSpKey(model.spKey());
        entity.setEntityId(model.entityId());
        entity.setDisplayName(model.displayName());
        entity.setAcsUrl(model.acsUrl());
        entity.setSloUrl(model.sloUrl());
        entity.setNameIdFormat(model.nameIdFormat());
        entity.setSignAssertion(model.signAssertion());
        entity.setRequireSignedAuthnRequest(model.requireSignedAuthnRequest());
        entity.setSpCertificate(model.spCertificate());
        entity.setAttributeMapping(writeMapping(model.attributeMapping()));
        entity.setEnabled(model.enabled());
        return entity;
    }

    /**
     * 解析属性映射 JSON。
     *
     * @param json 映射 JSON 文本
     * @return 映射键值;空值或非法内容返回空 Map
     */
    private Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 序列化属性映射。
     *
     * @param mapping 映射键值
     * @return JSON 文本;入参为空时返回空对象字面量
     * @throws IllegalStateException 序列化失败
     */
    private String writeMapping(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(mapping);
        } catch (Exception e) {
            throw new IllegalStateException("SAML 属性映射序列化失败", e);
        }
    }
}
