package club.beenest.iam.persistence.manager;

import club.beenest.iam.core.model.IdpMapperModel;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.persistence.entity.UserFederatedIdentity;
import club.beenest.iam.persistence.mapper.IdentityProviderMapper;
import club.beenest.iam.persistence.mapper.IdentityProviderMapperMapper;
import club.beenest.iam.persistence.mapper.UserFederatedIdentityMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 身份提供方存储(iam_identity_provider / iam_user_federated_identity /
 * iam_identity_provider_mapper)。
 * <p>配置以 JSON 存储,读取时解析为键值,解析失败按空配置处理。</p>
 */
@Component
public class IamIdentityProviderStore implements IdentityProviderStore {

    private final IdentityProviderMapper identityProviderMapper;
    private final UserFederatedIdentityMapper userFederatedIdentityMapper;
    private final IdentityProviderMapperMapper idpMapperMapper;
    private final ObjectMapper objectMapper;

    public IamIdentityProviderStore(IdentityProviderMapper identityProviderMapper,
                                    UserFederatedIdentityMapper userFederatedIdentityMapper,
                                    IdentityProviderMapperMapper idpMapperMapper,
                                    ObjectMapper objectMapper) {
        this.identityProviderMapper = identityProviderMapper;
        this.userFederatedIdentityMapper = userFederatedIdentityMapper;
        this.idpMapperMapper = idpMapperMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdentityProviderModel> findByIdp(Long realmId, String alias) {
        var entity = identityProviderMapper.selectByRealmIdAndAlias(realmId, alias);
        if (entity == null) {
            return Optional.empty();
        }
        // 解析 config JSONB 为配置键值
        Map<String, String> config = parseConfig(entity.getConfig());
        return Optional.of(new IdentityProviderModel(
                entity.getId(), entity.getRealmId(), entity.getProviderId(), entity.getAlias(),
                entity.getDisplayName(), Boolean.TRUE.equals(entity.getEnabled()), config));
    }

    @Override
    public List<IdentityProviderModel> listByRealm(Long realmId) {
        return identityProviderMapper.selectByRealmId(realmId).stream()
                .map(entity -> new IdentityProviderModel(
                        entity.getId(), entity.getRealmId(), entity.getProviderId(), entity.getAlias(),
                        entity.getDisplayName(), Boolean.TRUE.equals(entity.getEnabled()),
                        parseConfig(entity.getConfig())))
                .toList();
    }

    @Override
    public Optional<Long> findLinkedUserId(Long idpId, String externalId) {
        var linked = userFederatedIdentityMapper.selectByIdpAndExternalId(idpId, externalId);
        return linked == null ? Optional.empty() : Optional.of(linked.getUserId());
    }

    @Override
    public void link(Long realmId, Long userId, Long idpId, String externalId, String externalUsername) {
        var entity = new UserFederatedIdentity();
        entity.setRealmId(realmId);
        entity.setUserId(userId);
        entity.setIdpId(idpId);
        entity.setExternalId(externalId);
        entity.setExternalUsername(externalUsername);
        userFederatedIdentityMapper.insert(entity);
    }

    @Override
    public List<IdpMapperModel> listMappers(Long idpId) {
        return idpMapperMapper.selectByIdpId(idpId).stream()
                .map(mapper -> new IdpMapperModel(
                        mapper.getId(), mapper.getIdpId(), mapper.getMapperType(),
                        mapper.getSourceAttribute(), mapper.getTargetAttribute()))
                .toList();
    }

    @Override
    public int unlinkByIdp(Long idpId) {
        return userFederatedIdentityMapper.softDeleteByIdpId(idpId);
    }

    /**
     * 解析 config JSON。
     *
     * @param json 配置 JSON
     * @return 配置键值;空值或非法内容返回空 Map
     */
    private Map<String, String> parseConfig(String json) {
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
}
