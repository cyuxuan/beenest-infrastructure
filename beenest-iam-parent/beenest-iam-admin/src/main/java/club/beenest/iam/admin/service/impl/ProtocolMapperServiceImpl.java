package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.ProtocolMapperCreateRequest;
import club.beenest.iam.admin.dto.ProtocolMapperResponse;
import club.beenest.iam.admin.dto.ProtocolMapperUpdateRequest;
import club.beenest.iam.admin.service.ProtocolMapperService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.persistence.entity.ProtocolMapper;
import club.beenest.iam.persistence.manager.IamProtocolMapperManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 协议映射器管理实现。
 * <p>映射器限定在当前身份域;userAttribute 与 claimTemplate 二选一,claim 类型走白名单,
 * 客户端级映射必须挂靠本域客户端。</p>
 */
@Service
public class ProtocolMapperServiceImpl implements ProtocolMapperService {

    private static final List<String> VALID_CLAIM_TYPES = List.of("STRING", "NUMBER", "BOOLEAN", "JSON");

    private final IamProtocolMapperManager protocolMapperManager;
    private final ClientProvider clientProvider;

    public ProtocolMapperServiceImpl(IamProtocolMapperManager protocolMapperManager,
                                     ClientProvider clientProvider) {
        this.protocolMapperManager = protocolMapperManager;
        this.clientProvider = clientProvider;
    }

    @Override
    public PageResult<ProtocolMapperResponse> page(Long clientId, int pageNum, int pageSize) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 指定客户端时必须属于当前域
        if (clientId != null) {
            requireClientInRealm(clientId);
        }
        PageResult<ProtocolMapper> page = protocolMapperManager.page(realmId, clientId, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toResponse).toList());
    }

    @Override
    public ProtocolMapperResponse get(Long id) {
        return toResponse(requireInRealm(id));
    }

    @Override
    public ProtocolMapperResponse create(ProtocolMapperCreateRequest request) {
        Long realmId = CurrentUserHolder.require().getRealmId();
        // 1. 映射来源二选一
        boolean hasAttribute = request.userAttribute() != null && !request.userAttribute().isBlank();
        boolean hasTemplate = request.claimTemplate() != null && !request.claimTemplate().isBlank();
        if (hasAttribute == hasTemplate) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "userAttribute 与 claimTemplate 必须且只能提供一个");
        }
        // 2. 客户端级映射必须挂靠本域客户端
        if (request.clientId() != null) {
            requireClientInRealm(request.clientId());
        }
        // 3. claim 类型白名单
        String claimType = request.claimType() == null || request.claimType().isBlank()
                ? "STRING" : request.claimType().toUpperCase();
        if (!VALID_CLAIM_TYPES.contains(claimType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的 claim 类型: " + claimType);
        }
        ProtocolMapper mapper = new ProtocolMapper();
        mapper.setRealmId(realmId);
        mapper.setClientId(request.clientId());
        mapper.setName(request.name());
        mapper.setClaimName(request.claimName());
        mapper.setClaimType(claimType);
        mapper.setUserAttribute(hasAttribute ? request.userAttribute() : null);
        mapper.setClaimTemplate(hasTemplate ? request.claimTemplate() : null);
        protocolMapperManager.create(mapper);
        return toResponse(protocolMapperManager.getById(mapper.getId()));
    }

    @Override
    public ProtocolMapperResponse update(Long id, ProtocolMapperUpdateRequest request) {
        ProtocolMapper mapper = requireInRealm(id);
        if (request.claimType() != null) {
            String claimType = request.claimType().toUpperCase();
            if (!VALID_CLAIM_TYPES.contains(claimType)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "不支持的 claim 类型: " + claimType);
            }
            mapper.setClaimType(claimType);
        }
        if (request.name() != null) {
            mapper.setName(request.name());
        }
        if (request.claimName() != null) {
            mapper.setClaimName(request.claimName());
        }
        if (request.userAttribute() != null) {
            mapper.setUserAttribute(request.userAttribute());
            mapper.setClaimTemplate(null);
        }
        if (request.claimTemplate() != null) {
            mapper.setClaimTemplate(request.claimTemplate());
            mapper.setUserAttribute(null);
        }
        protocolMapperManager.update(mapper);
        return toResponse(protocolMapperManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        requireInRealm(id);
        protocolMapperManager.remove(id);
    }

    /**
     * 校验客户端存在且属于当前域。
     *
     * @param clientId 客户端主键
     * @throws BusinessException 客户端不存在或不属于当前域
     */
    private void requireClientInRealm(Long clientId) {
        ClientModel client = clientProvider.findById(clientId).orElse(null);
        if (client == null || !CurrentUserHolder.require().getRealmId().equals(client.realmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "客户端不存在: " + clientId);
        }
    }

    /**
     * 读取属于当前域的协议映射器。
     *
     * @param id 协议映射器主键
     * @return 协议映射器
     * @throws BusinessException 协议映射器不存在或不属于当前域
     */
    private ProtocolMapper requireInRealm(Long id) {
        ProtocolMapper mapper = protocolMapperManager.getById(id);
        if (mapper == null || !CurrentUserHolder.require().getRealmId().equals(mapper.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "协议映射器不存在: " + id);
        }
        return mapper;
    }

    /**
     * 协议映射器转响应。
     *
     * @param mapper 协议映射器
     * @return 协议映射器响应
     */
    private ProtocolMapperResponse toResponse(ProtocolMapper mapper) {
        return new ProtocolMapperResponse(mapper.getId(), mapper.getClientId(), mapper.getName(),
                mapper.getClaimName(), mapper.getClaimType(), mapper.getUserAttribute(),
                mapper.getClaimTemplate(), mapper.getCreatedTime());
    }
}
