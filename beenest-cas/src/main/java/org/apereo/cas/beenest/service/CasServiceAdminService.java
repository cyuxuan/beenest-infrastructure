package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.common.enums.LoginType;
import org.apereo.cas.beenest.dto.CasServiceAuthMethodDTO;
import org.apereo.cas.beenest.dto.CasServiceDetailDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterResultDTO;
import org.apereo.cas.beenest.dto.CasServiceSummaryDTO;
import org.apereo.cas.beenest.entity.CasAppAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.services.CasRegisteredService;
import org.apereo.cas.services.DefaultRegisteredServiceAuthenticationPolicy;
import org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.ReturnAllowedAttributeReleasePolicy;
import org.springframework.beans.factory.annotation.Autowired;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * CAS 服务管理服务
 * <p>
 * 负责 registered service 的构建、更新与可用认证方式映射。
 * controller 只负责 HTTP 层，不直接拼装 CAS 服务对象。
 */
@Slf4j
@RequiredArgsConstructor
public class CasServiceAdminService {

    private static final List<String> DEFAULT_ALLOWED_ATTRIBUTES = List.of(
            "userId", "username", "userType", "phone", "email", "nickname", "loginType"
    );

    private final ServicesManager servicesManager;
    private final AppAccessService appAccessService;
    private final List<AuthenticationHandler> authenticationHandlers;
    @Autowired
    private CasServiceCredentialService credentialService;

    public RegisteredService createService(CasServiceRegisterDTO dto) {
        CasRegisteredService service = buildService(dto);
        servicesManager.save(service);
        return service;
    }

    /**
     * 注册应用并同时签发一次性服务 secret。
     *
     * @param dto 应用注册信息
     * @return 注册结果，包含服务摘要和一次性明文 secret
     */
    public CasServiceRegisterResultDTO createServiceWithSecret(CasServiceRegisterDTO dto) {
        CasRegisteredService service = buildService(dto);
        servicesManager.save(service);

        CasServiceCredentialService.IssuedCredential issuedCredential = requireCredentialService().issueCredential(service.getId());
        CasServiceRegisterResultDTO result = new CasServiceRegisterResultDTO();
        copySummary(result, service);
        result.setPlainSecret(issuedCredential.plainSecret());
        result.setSecretVersion(issuedCredential.secretVersion());
        return result;
    }

    public RegisteredService updateService(Long id, CasServiceRegisterDTO dto) {
        RegisteredService existing = servicesManager.findServiceBy(id);
        if (existing == null) {
            throw new BusinessException(404, "应用不存在");
        }
        if (!(existing instanceof CasRegisteredService service)) {
            throw new BusinessException(400, "仅支持 CAS registered service");
        }

        applyUpdates(service, dto);
        servicesManager.save(service);
        return service;
    }

    public void deleteService(Long id) {
        servicesManager.delete(id);
    }

    public List<CasServiceSummaryDTO> listServices() {
        List<CasServiceSummaryDTO> result = new ArrayList<>();
        for (RegisteredService svc : servicesManager.getAllServices()) {
            result.add(toServiceView(svc));
        }
        return result;
    }

    public CasServiceDetailDTO getService(Long id) {
        RegisteredService svc = servicesManager.findServiceBy(id);
        if (svc == null) {
            throw new BusinessException(404, "应用不存在");
        }
        return toServiceDetail(svc);
    }

    public CasRegisteredService buildService(CasServiceRegisterDTO dto) {
        CasRegisteredService service = new CasRegisteredService();
        service.setName(dto.getName());
        service.setServiceId(dto.getServiceId());
        service.setDescription(dto.getDescription());
        service.setId(dto.getId() != null ? dto.getId() : resolveServiceId());

        if (dto.getLogoutUrl() != null) {
            service.setLogoutUrl(dto.getLogoutUrl());
        }
        if (dto.getLogoutType() != null) {
            service.setLogoutType(org.apereo.cas.services.RegisteredServiceLogoutType.valueOf(dto.getLogoutType()));
        }

        service.setAttributeReleasePolicy(buildAttributeReleasePolicy(dto.getAllowedAttributes()));
        service.setAccessStrategy(buildAccessStrategy(dto.getAccessControlEnabled()));
        applyAuthenticationPolicy(service, dto.getAllowedAuthenticationHandlers());
        return service;
    }

    public List<CasServiceAuthMethodDTO> getAuthMethods() {
        List<CasServiceAuthMethodDTO> methods = new ArrayList<>();
        for (LoginType type : LoginType.values()) {
            methods.add(new CasServiceAuthMethodDTO(
                    type.getCode(),
                    type.getDescription(),
                    type.getHandlerName()
            ));
        }
        return methods;
    }

    public void grantAccess(Long serviceId, String userId, String accessLevel, String grantedBy,
                            String reason, java.time.LocalDateTime expireTime) {
        appAccessService.grantAccess(userId, serviceId, accessLevel, grantedBy, reason, expireTime);
    }

    public void batchGrantAccess(Long serviceId, List<String> userIds, String accessLevel, String grantedBy, String reason) {
        appAccessService.batchGrantAccess(userIds, serviceId, accessLevel, grantedBy, reason);
    }

    public void revokeAccess(Long serviceId, String userId) {
        appAccessService.revokeAccess(userId, serviceId);
    }

    public List<CasAppAccess> getAppUsers(Long serviceId) {
        return appAccessService.getAppUsers(serviceId);
    }

    private void applyUpdates(CasRegisteredService service, CasServiceRegisterDTO dto) {
        if (dto.getName() != null) service.setName(dto.getName());
        if (dto.getServiceId() != null) service.setServiceId(dto.getServiceId());
        if (dto.getDescription() != null) service.setDescription(dto.getDescription());
        if (dto.getLogoutUrl() != null) service.setLogoutUrl(dto.getLogoutUrl());
        if (dto.getLogoutType() != null) {
            service.setLogoutType(org.apereo.cas.services.RegisteredServiceLogoutType.valueOf(dto.getLogoutType()));
        }

        if (dto.getAllowedAttributes() != null) {
            service.setAttributeReleasePolicy(buildAttributeReleasePolicy(dto.getAllowedAttributes()));
        }

        if (dto.getAccessControlEnabled() != null) {
            service.setAccessStrategy(buildAccessStrategy(dto.getAccessControlEnabled()));
        }

        if (dto.getAllowedAuthenticationHandlers() != null) {
            applyAuthenticationPolicy(service, dto.getAllowedAuthenticationHandlers());
        }
    }

    private ReturnAllowedAttributeReleasePolicy buildAttributeReleasePolicy(List<String> allowedAttributes) {
        ReturnAllowedAttributeReleasePolicy attrPolicy = new ReturnAllowedAttributeReleasePolicy();
        if (allowedAttributes != null && !allowedAttributes.isEmpty()) {
            attrPolicy.setAllowedAttributes(allowedAttributes);
        } else {
            attrPolicy.setAllowedAttributes(DEFAULT_ALLOWED_ATTRIBUTES);
        }
        return attrPolicy;
    }

    private org.apereo.cas.services.RegisteredServiceAccessStrategy buildAccessStrategy(Boolean accessControlEnabled) {
        if (Boolean.FALSE.equals(accessControlEnabled)) {
            DefaultRegisteredServiceAccessStrategy accessStrategy = new DefaultRegisteredServiceAccessStrategy();
            accessStrategy.setEnabled(true);
            accessStrategy.setSsoEnabled(true);
            return accessStrategy;
        }

        BeenestAccessStrategy accessStrategy = new BeenestAccessStrategy();
        accessStrategy.setEnabled(true);
        accessStrategy.setSsoEnabled(true);
        return accessStrategy;
    }

    private void applyAuthenticationPolicy(CasRegisteredService service, List<String> allowedAuthenticationHandlers) {
        if (allowedAuthenticationHandlers == null || allowedAuthenticationHandlers.isEmpty()) {
            service.setAuthenticationPolicy(null);
            return;
        }

        Set<String> handlerNames = resolveHandlerNames(allowedAuthenticationHandlers);
        DefaultRegisteredServiceAuthenticationPolicy authPolicy = new DefaultRegisteredServiceAuthenticationPolicy();
        authPolicy.setRequiredAuthenticationHandlers(handlerNames);
        service.setAuthenticationPolicy(authPolicy);
    }

    private Set<String> resolveHandlerNames(List<String> authTypes) {
        Set<String> availableHandlers = new HashSet<>();
        if (authenticationHandlers != null) {
            for (AuthenticationHandler handler : authenticationHandlers) {
                if (handler != null && handler.getName() != null) {
                    availableHandlers.add(handler.getName());
                }
            }
        }

        Set<String> resolved = new TreeSet<>();
        for (String authType : authTypes) {
            String handlerName;
            try {
                handlerName = LoginType.handlerNameOf(authType);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "认证方式不可用: " + authType);
            }
            if (!availableHandlers.contains(handlerName)) {
                throw new BusinessException(400, "认证方式不可用: " + authType);
            }
            resolved.add(handlerName);
        }
        return resolved;
    }

    private CasServiceSummaryDTO toServiceView(RegisteredService svc) {
        CasServiceSummaryDTO item = new CasServiceSummaryDTO();
        copySummary(item, svc);
        return item;
    }

    private CasServiceDetailDTO toServiceDetail(RegisteredService svc) {
        CasServiceDetailDTO data = new CasServiceDetailDTO();
        data.setId(svc.getId());
        data.setName(svc.getName());
        data.setServiceId(svc.getServiceId());
        data.setDescription(svc.getDescription());
        if (svc instanceof CasRegisteredService casSvc) {
            data.setLogoutUrl(casSvc.getLogoutUrl());
        }
        data.setAttributeReleasePolicy(svc.getAttributeReleasePolicy());
        data.setAuthenticationPolicy(svc.getAuthenticationPolicy());
        data.setAccessStrategy(svc.getAccessStrategy());
        return data;
    }

    private void copySummary(CasServiceSummaryDTO target, RegisteredService source) {
        target.setId(source.getId());
        target.setName(source.getName());
        target.setServiceId(source.getServiceId());
        target.setDescription(source.getDescription());
    }

    private CasServiceCredentialService requireCredentialService() {
        if (credentialService == null) {
            throw new IllegalStateException("CAS 服务凭证服务未初始化");
        }
        return credentialService;
    }

    private Long resolveServiceId() {
        long candidate = System.currentTimeMillis();
        while (servicesManager.findServiceBy(candidate) != null) {
            candidate++;
        }
        return candidate;
    }
}
