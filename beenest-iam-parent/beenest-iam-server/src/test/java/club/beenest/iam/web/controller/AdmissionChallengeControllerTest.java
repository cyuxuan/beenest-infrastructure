package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挑战页控制器测试。
 * <p>挑战页只展示"白名单 ∩ 域级可用方式"里的方式:
 * 多展示一个用户就会去试,而试错的过程本身会泄露"这个应用接受什么"。</p>
 */
class AdmissionChallengeControllerTest {

    @Test
    @DisplayName("挑战页只展示白名单与域级可用方式的交集")
    void showsOnlyIntersection() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(
                new RealmModel(1L, "default", "默认域", true, false, 300L, 1800L,
                        1800L, 36000L, null, null)));
        when(themeSelector.view(anyString())).thenReturn("login/challenge");
        when(catalog.availableInRealm(1L, "default")).thenReturn(Set.of(LoginMethod.PASSWORD, LoginMethod.SMS));

        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS", "WECHAT"), false,
                "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);

        AdmissionChallengeController controller = new AdmissionChallengeController(
                challengeService, catalog, realmProvider, themeSelector,
                mock(AuthService.class),
                mock(club.beenest.iam.authz.session.IamLoginSessionService.class),
                mock(club.beenest.iam.authz.mfa.MfaPendingService.class),
                mock(club.beenest.iam.core.spi.provider.IdentityProviderStore.class),
                mock(club.beenest.iam.authz.session.IamUserSessionService.class),
                mock(club.beenest.iam.authz.mfa.MfaService.class));
        Model model = mock(Model.class);

        controller.challengePage("default", "tok", null, model, null);

        verify(model).addAttribute("allowedMethods", Set.of("SMS"));
    }
}
