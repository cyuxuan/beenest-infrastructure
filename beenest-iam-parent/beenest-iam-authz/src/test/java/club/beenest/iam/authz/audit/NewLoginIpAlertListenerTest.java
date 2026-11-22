package club.beenest.iam.authz.audit;

import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新 IP 登录提醒测试:首次登录不提醒、同网段不提醒、跨网段提醒一次、失败事件不参与。
 */
class NewLoginIpAlertListenerTest {

    private static final String REALM = "default";
    private static final long REALM_ID = 1L;
    private static final long USER_ID = 7L;

    private FakeRedis redis;
    private SecurityAlertService alertService;
    private NewLoginIpAlertListener listener;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        RealmProvider realmProvider = Mockito.mock(RealmProvider.class);
        alertService = Mockito.mock(SecurityAlertService.class);
        when(realmProvider.findByRealmKey(REALM)).thenReturn(Optional.of(new RealmModel(
                REALM_ID, REALM, "默认域", true, true, 300L, 1800L, 1800L, 36000L, null, null)));
        listener = new NewLoginIpAlertListener(redis, realmProvider, alertService);
    }

    @Test
    @DisplayName("首次登录:记录基线,不提醒")
    void firstLoginOnlyRecordsBaseline() {
        listener.onLoginEvent(success("10.1.2.3"));

        assertTrue(redis.store.containsKey(key()));
        verify(alertService, never()).alertNewLoginIp(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("同一 /24 内换地址:视为同网段,不提醒")
    void sameSubnetDoesNotAlert() {
        listener.onLoginEvent(success("10.1.2.3"));
        listener.onLoginEvent(success("10.1.2.99"));

        verify(alertService, never()).alertNewLoginIp(any(), any(), anyString(), anyString(), any());
        assertTrue(redis.store.get(key()).equals("10.1.2.99"), "基线应跟随最新地址");
    }

    @Test
    @DisplayName("跨网段:提醒一次,同一新地址不重复提醒")
    void newSubnetAlertsOnce() {
        listener.onLoginEvent(success("10.1.2.3"));
        listener.onLoginEvent(success("192.168.9.8"));

        verify(alertService).alertNewLoginIp(REALM_ID, USER_ID, "192.168.9.8", "10.1.2.3", "PASSWORD");

        listener.onLoginEvent(success("192.168.9.8"));
        verify(alertService, Mockito.times(1))
                .alertNewLoginIp(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("登录失败不参与基线")
    void ignoresFailureEvents() {
        listener.onLoginEvent(new LoginEventEvent(REALM, USER_ID, "alice",
                LoginEventEvent.LOGIN_FAILURE, "PASSWORD", "10.1.2.3", "ua", "口令错误",
                LocalDateTime.now()));

        assertFalse(redis.store.containsKey(key()));
    }

    @Test
    @DisplayName("缺少用户或 IP:直接跳过")
    void skipsIncompleteEvents() {
        listener.onLoginEvent(new LoginEventEvent(REALM, null, "alice",
                LoginEventEvent.LOGIN_SUCCESS, "PASSWORD", "10.1.2.3", "ua", null, LocalDateTime.now()));
        listener.onLoginEvent(new LoginEventEvent(REALM, USER_ID, "alice",
                LoginEventEvent.LOGIN_SUCCESS, "PASSWORD", " ", "ua", null, LocalDateTime.now()));

        assertTrue(redis.store.isEmpty());
        verify(alertService, never()).alertNewLoginIp(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("服务端换码登录:IP 属于调用方应用,不参与基线也不提醒")
    void ignoresProviderCodeLogins() {
        listener.onLoginEvent(success("10.1.2.3"));
        listener.onLoginEvent(new LoginEventEvent(REALM, USER_ID, "alice",
                LoginEventEvent.LOGIN_SUCCESS, "PROVIDER", "203.0.113.10", "ua", null,
                LocalDateTime.now()));

        assertTrue(redis.store.get(key()).equals("10.1.2.3"), "基线不应被调用方地址覆盖");
        verify(alertService, never()).alertNewLoginIp(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Redis 不可用:不抛出异常,登录不受影响")
    void redisFailureDoesNotThrow() {
        RedisUtils broken = Mockito.mock(RedisUtils.class);
        when(broken.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        NewLoginIpAlertListener failing = new NewLoginIpAlertListener(
                broken, Mockito.mock(RealmProvider.class), alertService);

        assertDoesNotThrow(() -> failing.onLoginEvent(success("10.1.2.3")));
    }

    @Test
    @DisplayName("网段归并口径:IPv4 /24、IPv6 /64、映射地址按 IPv4 比")
    void networkComparison() {
        assertTrue(NewLoginIpAlertListener.sameNetwork("10.1.2.3", "10.1.2.200"));
        assertFalse(NewLoginIpAlertListener.sameNetwork("10.1.2.3", "10.1.3.3"));
        assertTrue(NewLoginIpAlertListener.sameNetwork("2001:db8:1:2::1", "2001:db8:1:2::ffff"));
        assertFalse(NewLoginIpAlertListener.sameNetwork("2001:db8:1:2::1", "2001:db8:1:3::1"));
        assertTrue(NewLoginIpAlertListener.sameNetwork("::ffff:10.1.2.3", "10.1.2.4"));
        assertFalse(NewLoginIpAlertListener.sameNetwork("10.1.2.3", "2001:db8::1"));
        assertFalse(NewLoginIpAlertListener.sameNetwork("not-an-ip", "10.1.2.3"));
    }

    private static LoginEventEvent success(String ip) {
        return new LoginEventEvent(REALM, USER_ID, "alice", LoginEventEvent.LOGIN_SUCCESS,
                "PASSWORD", ip, "ua", null, LocalDateTime.now());
    }

    private static String key() {
        return "iam:default:loginip:" + USER_ID;
    }

    /** 内存 Redis 替身:覆盖本链路用到的读写。 */
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
    }
}
