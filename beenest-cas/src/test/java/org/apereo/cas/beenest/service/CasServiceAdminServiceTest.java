package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy;
import org.apereo.cas.beenest.config.CasServiceCredentialProperties;
import org.apereo.cas.beenest.dto.CasServiceDetailDTO;
import org.apereo.cas.beenest.dto.CasServiceAuthMethodDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterResultDTO;
import org.apereo.cas.beenest.dto.CasServiceRegisterDTO;
import org.apereo.cas.beenest.dto.CasServiceSummaryDTO;
import org.apereo.cas.beenest.entity.CasServiceCredentialDO;
import org.apereo.cas.beenest.mapper.CasServiceCredentialMapper;
import org.apereo.cas.beenest.util.AesEncryptionUtils;
import org.apereo.cas.beenest.service.CasServiceCredentialService.IssuedCredential;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.any;

class CasServiceAdminServiceTest {

    @Test
    void accessControlDisabledUsesDefaultAccessStrategy() {
        ServicesManager servicesManager = mock(ServicesManager.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        CasServiceAdminService service = new CasServiceAdminService(servicesManager, appAccessService, List.of());

        CasServiceRegisterDTO dto = new CasServiceRegisterDTO();
        dto.setName("demo");
        dto.setServiceId("^https://demo.example.com/.*");
        dto.setAccessControlEnabled(false);

        RegisteredService registered = service.buildService(dto);

        assertThat(registered.getAccessStrategy()).isInstanceOf(DefaultRegisteredServiceAccessStrategy.class);
        assertThat(registered.getAccessStrategy()).isNotInstanceOf(BeenestAccessStrategy.class);
    }

    @Test
    void usernamePasswordMapsToAppTokenHandler() {
        ServicesManager servicesManager = mock(ServicesManager.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        AuthenticationHandler appHandler = mock(AuthenticationHandler.class);
        when(appHandler.getName()).thenReturn("appTokenAuthenticationHandler");
        CasServiceAdminService service = new CasServiceAdminService(servicesManager, appAccessService, List.of(appHandler));

        CasServiceRegisterDTO dto = new CasServiceRegisterDTO();
        dto.setName("demo");
        dto.setServiceId("^https://demo.example.com/.*");
        dto.setAllowedAuthenticationHandlers(List.of("USERNAME_PASSWORD"));

        RegisteredService registered = service.buildService(dto);

        assertThat(registered.getAuthenticationPolicy()).isNotNull();
        assertThat(service.getAuthMethods())
                .anySatisfy(method -> {
                    assertThat(method).isInstanceOf(CasServiceAuthMethodDTO.class);
                    assertThat(method.getCode()).isEqualTo("USERNAME_PASSWORD");
                    assertThat(method.getHandler()).isEqualTo("appTokenAuthenticationHandler");
                });
    }

    @Test
    void buildServiceAvoidsServiceIdCollision() {
        ServicesManager servicesManager = mock(ServicesManager.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        CasServiceAdminService service = new CasServiceAdminService(servicesManager, appAccessService, List.of());

        AtomicBoolean firstLookup = new AtomicBoolean(true);
        AtomicLong collidedId = new AtomicLong(-1L);
        when(servicesManager.findServiceBy(anyLong())).thenAnswer(invocation -> {
            if (firstLookup.getAndSet(false)) {
                collidedId.set(invocation.getArgument(0));
                return mock(RegisteredService.class);
            }
            return null;
        });

        CasServiceRegisterDTO dto = new CasServiceRegisterDTO();
        dto.setName("demo");
        dto.setServiceId("^https://demo.example.com/.*");

        RegisteredService registered = service.buildService(dto);

        assertThat(registered.getId()).isNotNull();
        assertThat(collidedId.get()).isGreaterThan(0L);
        assertThat(registered.getId()).isGreaterThan(collidedId.get());
    }

    @Test
    void createServiceWithSecretReturnsPlainSecretOnceAndPersistsHashedCredential() {
        ServicesManager servicesManager = mock(ServicesManager.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        CasServiceCredentialMapper credentialMapper = mock(CasServiceCredentialMapper.class);
        CasServiceCredentialProperties properties = new CasServiceCredentialProperties();
        CasServiceCredentialService credentialService = spy(new CasServiceCredentialService(credentialMapper, properties));
        doReturn("plain-secret-123").when(credentialService).generatePlainSecret();

        AtomicReference<CasServiceCredentialDO> storedCredential = new AtomicReference<>();
        doAnswer(invocation -> {
            storedCredential.set(invocation.getArgument(0));
            return null;
        }).when(credentialMapper).insert(any(CasServiceCredentialDO.class));
        when(credentialMapper.selectByServiceId(anyLong())).thenAnswer(invocation -> storedCredential.get());

        CasServiceAdminService service = new CasServiceAdminService(servicesManager, appAccessService, List.of());
        ReflectionTestUtils.setField(service, "credentialService", credentialService);

        CasServiceRegisterDTO dto = new CasServiceRegisterDTO();
        dto.setName("demo");
        dto.setServiceId("^https://demo.example.com/.*");

        CasServiceRegisterResultDTO result = service.createServiceWithSecret(dto);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getName()).isEqualTo("demo");
        assertThat(result.getServiceId()).isEqualTo("^https://demo.example.com/.*");
        assertThat(result.getPlainSecret()).isEqualTo("plain-secret-123");
        assertThat(result.getSecretVersion()).isEqualTo(1L);

        verify(credentialMapper).insert(any(CasServiceCredentialDO.class));
        CasServiceCredentialDO inserted = credentialService.getCredential(result.getId());
        assertThat(inserted).isNotNull();
        assertThat(inserted.getSecretHash()).isNotBlank();
        assertThat(inserted.getSecretHash()).isNotEqualTo("plain-secret-123");
        assertThat(AesEncryptionUtils.decrypt(inserted.getSecretHash(), properties.getEncryptionKey()))
                .isEqualTo("plain-secret-123");
        assertThat(inserted.getSecretVersion()).isEqualTo(1L);
        assertThat(inserted.getState()).isEqualTo("ACTIVE");
    }

    @Test
    void summaryAndDetailDtosDoNotDeclareSecretFields() {
        assertThat(Arrays.stream(CasServiceSummaryDTO.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("secret", "plainSecret", "secretHash", "secretVersion");
        assertThat(Arrays.stream(CasServiceDetailDTO.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("secret", "plainSecret", "secretHash", "secretVersion");
    }
}
