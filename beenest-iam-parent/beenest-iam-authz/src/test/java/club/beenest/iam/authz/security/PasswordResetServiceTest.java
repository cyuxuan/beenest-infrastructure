package club.beenest.iam.authz.security;

import club.beenest.iam.authz.notification.NotificationService;
import club.beenest.iam.authz.sms.SmsCodeService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 找回口令服务单元测试。
 * <p>固化四条口径:</p>
 * <ul>
 *   <li>账号不存在时静默返回,不抛错、不发送——否则响应差异即账号枚举接口;</li>
 *   <li>命中账号时签发票据并投递,链接里只带票据不带用户标识;</li>
 *   <li>票据一次性消费,重放必失败;</li>
 *   <li>票据缺失/无效统一报"链接无效",不区分"过期"与"不存在"。</li>
 * </ul>
 */
class PasswordResetServiceTest {

    private static final String REALM = "default";
    private static final long REALM_ID = 1L;
    private static final String EMAIL = "alice@example.com";

    private UserProvider userProvider;
    private RealmProvider realmProvider;
    private PasswordChangeService passwordChangeService;
    private NotificationService notificationService;
    private SmsCodeService smsCodeService;
    private IamNotificationLogManager notificationLogManager;
    private FakeRedis redis;
    private IamRateLimiter rateLimiter;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userProvider = Mockito.mock(UserProvider.class);
        realmProvider = Mockito.mock(RealmProvider.class);
        passwordChangeService = Mockito.mock(PasswordChangeService.class);
        notificationService = Mockito.mock(NotificationService.class);
        smsCodeService = Mockito.mock(SmsCodeService.class);
        notificationLogManager = Mockito.mock(IamNotificationLogManager.class);
        rateLimiter = Mockito.mock(IamRateLimiter.class);
        redis = new FakeRedis();
        service = new PasswordResetService(userProvider, realmProvider, passwordChangeService,
                notificationService, smsCodeService, notificationLogManager, redis, rateLimiter, 30);

        when(realmProvider.findByRealmKey(REALM)).thenReturn(Optional.of(realm()));
        when(notificationService.mailAvailable(REALM_ID)).thenReturn(true);
        when(notificationService.sendMail(any(), anyString(), any(), anyString(),
                anyString(), anyString(), any())).thenReturn(true);
    }

    private static RealmModel realm() {
        return new RealmModel(REALM_ID, REALM, "默认域", true, true,
                300L, 1800L, 1800L, 36000L, null, null);
    }

    private static UserModel user() {
        return new UserModel(7L, REALM_ID, "alice", "爱丽丝", EMAIL, null, 1, "INTERNAL",
                true, 0, Map.of());
    }

    @Test
    @DisplayName("账号不存在:静默返回,不投递")
    void silentlyIgnoresUnknownAccount() {
        when(userProvider.findByEmail(REALM_ID, EMAIL)).thenReturn(Optional.empty());
        service.requestReset(REALM, EMAIL, "10.0.0.1", "https://iam.example.com");
        verify(notificationService, never()).sendMail(any(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("邮件路径:签发票据并投递,链接只带票据")
    void issuesTicketAndSendsMail() {
        when(userProvider.findByEmail(REALM_ID, EMAIL)).thenReturn(Optional.of(user()));
        service.requestReset(REALM, EMAIL, "10.0.0.1", "https://iam.example.com");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).sendMail(eq(REALM_ID), eq(REALM), eq(7L), eq(EMAIL),
                eq(NotificationService.TEMPLATE_PASSWORD_RESET), anyString(), variables.capture());
        String resetUrl = String.valueOf(variables.getValue().get("resetUrl"));
        assertTrue(resetUrl.startsWith("https://iam.example.com/reset-password?realm=default&ticket="));
        assertFalse(resetUrl.contains("userId"), "链接不得携带用户标识");

        String ticket = resetUrl.substring(resetUrl.indexOf("ticket=") + "ticket=".length());
        assertEquals("7", redis.store.get("iam:default:pwreset:" + ticket));
    }

    @Test
    @DisplayName("确认:票据一次性消费,重放失败")
    void ticketIsConsumedOnce() {
        when(userProvider.findByEmail(REALM_ID, EMAIL)).thenReturn(Optional.of(user()));
        service.requestReset(REALM, EMAIL, "10.0.0.1", "https://iam.example.com");
        String ticket = firstIssuedTicket();

        service.confirmReset(REALM, ticket, "N3w!Passw0rd");
        verify(passwordChangeService).changePassword(REALM_ID, 7L, "N3w!Passw0rd");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirmReset(REALM, ticket, "Another!Passw0rd"));
        assertEquals(ErrorCode.RESET_TICKET_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("票据缺失或不存在:统一报链接无效")
    void rejectsMissingTicket() {
        assertEquals(ErrorCode.RESET_TICKET_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> service.confirmReset(REALM, "  ", "N3w!Passw0rd")).getCode());
        assertEquals(ErrorCode.RESET_TICKET_INVALID.getCode(),
                assertThrows(BusinessException.class,
                        () -> service.confirmReset(REALM, "no-such-ticket", "N3w!Passw0rd")).getCode());
    }

    @Test
    @DisplayName("账号维度节流:60 秒内重复申请不再投递")
    void throttlesRepeatedRequests() {
        when(userProvider.findByEmail(REALM_ID, EMAIL)).thenReturn(Optional.of(user()));
        service.requestReset(REALM, EMAIL, "10.0.0.1", "https://iam.example.com");
        service.requestReset(REALM, EMAIL, "10.0.0.2", "https://iam.example.com");
        verify(notificationService, Mockito.times(1)).sendMail(any(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("邮件通道不可用:不投递,也不抛错")
    void skipsWhenMailUnavailable() {
        when(notificationService.mailAvailable(REALM_ID)).thenReturn(false);
        when(userProvider.findByEmail(REALM_ID, EMAIL)).thenReturn(Optional.of(user()));
        service.requestReset(REALM, EMAIL, "10.0.0.1", "https://iam.example.com");
        verify(notificationService, never()).sendMail(any(), anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    /** 从 Redis 里取出唯一一枚票据(测试只签发一枚)。 */
    private String firstIssuedTicket() {
        for (Map.Entry<String, String> entry : redis.store.entrySet()) {
            if (entry.getKey().startsWith("iam:default:pwreset:")) {
                String ticket = entry.getKey().substring("iam:default:pwreset:".length());
                assertNotNull(ticket);
                return ticket;
            }
        }
        throw new AssertionError("未签发任何票据");
    }

    /** 内存 Redis 替身:覆盖找回口令链路用到的方法。 */
    static class FakeRedis extends RedisUtils {
        final Map<String, String> store = new ConcurrentHashMap<>();

        FakeRedis() {
            super(null, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            store.put(key, value);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }

        @Override
        public String getAndDelete(String key) {
            return store.remove(key);
        }

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            return store.putIfAbsent(key, value) == null;
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }
    }
}
