package club.beenest.iam.authz.audit;

import club.beenest.iam.authz.notification.SecurityAlertService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * 新 IP 登录提醒。
 * <p>账号被接管后,攻击者通常保持原口令以免打草惊蛇,凭证变更提醒因此可能一声不响。
 * 登录来源变化是另一条独立线索:同一账号突然从陌生地址登录,本人往往第一时间能判断
 * "不是我"。</p>
 * <p>判断口径是"上次成功登录 IP",按网段归并:IPv4 比到 /24、IPv6 比到 /64。
 * 运营商与移动网络会在同一网段内换地址,逐位比较会让提醒变成噪音,用户很快学会无视它。
 * 首次登录没有基线,不提醒;换 IP 后立即更新基线,同一新地址只提醒一次。</p>
 * <p>服务端换码的登录类型不参与:这类请求的地址是调用方应用的地址而非用户地址,
 * 计入基线会让用户在两端来回登录时反复收到误报。</p>
 */
@Component
public class NewLoginIpAlertListener {

    private static final Logger log = LoggerFactory.getLogger(NewLoginIpAlertListener.class);

    /** 基线保留时长:超过半年的"上次登录"参考价值有限 */
    private static final Duration REMEMBER = Duration.ofDays(180);
    /** 域标识缺失时的键前缀占位 */
    private static final String GLOBAL_REALM = "global";
    /** 服务端换码登录:IP 属于调用方应用,不代表用户来源 */
    private static final String PROVIDER_LOGIN_TYPE = "PROVIDER";

    private final RedisUtils redisUtils;
    private final RealmProvider realmProvider;
    private final SecurityAlertService securityAlertService;

    public NewLoginIpAlertListener(RedisUtils redisUtils,
                                   RealmProvider realmProvider,
                                   SecurityAlertService securityAlertService) {
        this.redisUtils = redisUtils;
        this.realmProvider = realmProvider;
        this.securityAlertService = securityAlertService;
    }

    /**
     * 消费登录成功事件,比对来源网段。
     * <p>任何异常都只记日志:提醒是旁路能力,不能影响登录本身。</p>
     *
     * @param event 登录事件
     */
    @EventListener
    public void onLoginEvent(LoginEventEvent event) {
        if (!LoginEventEvent.LOGIN_SUCCESS.equals(event.eventType())) {
            return;
        }
        if (event.userId() == null || event.ip() == null || event.ip().isBlank()) {
            return;
        }
        if (PROVIDER_LOGIN_TYPE.equalsIgnoreCase(event.loginType())) {
            return;
        }
        String currentIp = event.ip().trim();
        try {
            String key = lastIpKey(event.realmKey(), event.userId());
            String previousIp = redisUtils.get(key);
            redisUtils.set(key, currentIp, REMEMBER);
            if (previousIp == null || previousIp.isBlank() || sameNetwork(previousIp, currentIp)) {
                return;
            }
            Long realmId = resolveRealmId(event.realmKey());
            if (realmId == null) {
                return;
            }
            log.info("检测到新 IP 登录: userId={}, previous={}, current={}",
                    event.userId(), previousIp, currentIp);
            securityAlertService.alertNewLoginIp(realmId, event.userId(), currentIp,
                    previousIp, event.loginType());
        } catch (RuntimeException e) {
            log.warn("新 IP 登录提醒失败: userId={}, error={}", event.userId(), e.getMessage());
        }
    }

    /**
     * 判断两个地址是否属于同一网段(IPv4 /24、IPv6 /64)。
     * <p>无法解析或地址族不同时按"不同"处理:宁可多提醒一次,也不要漏掉跨地址族的变化。</p>
     *
     * @param left  地址一
     * @param right 地址二
     * @return true 表示同网段
     */
    static boolean sameNetwork(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        byte[] first = normalized(a);
        byte[] second = normalized(b);
        if (first == null || second == null || first.length != second.length) {
            return false;
        }
        int prefix = first.length == 4 ? 3 : 8;
        for (int i = 0; i < prefix; i++) {
            if (first[i] != second[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析并归一化地址;IPv4 映射的 IPv6 地址还原成 4 字节。
     *
     * @param value 地址文本
     * @return 地址字节;无法解析时返回 null
     */
    private static byte[] normalized(String value) {
        // 只解析形如 IP 的字符串,避免把任意文本交给 DNS 解析
        if (!value.contains(".") && !value.contains(":")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            if (address instanceof Inet6Address && isIpv4Mapped(bytes)) {
                return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
            }
            return bytes;
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    /**
     * 是否为 IPv4 映射的 IPv6 地址(::ffff:a.b.c.d)。
     *
     * @param bytes 16 字节地址
     * @return true 表示为映射地址
     */
    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    /**
     * 上次登录 IP 的 Redis 键。
     *
     * @param realmKey 域标识
     * @param userId   用户主键
     * @return Redis 键
     */
    private static String lastIpKey(String realmKey, Long userId) {
        String realm = realmKey == null || realmKey.isBlank() ? GLOBAL_REALM : realmKey;
        return String.format(IamRedisKeyConstants.LOGIN_LAST_IP, realm, userId);
    }

    /**
     * 按域标识回查域主键。
     *
     * @param realmKey 域标识
     * @return 域主键;域不存在时返回 null
     */
    private Long resolveRealmId(String realmKey) {
        if (realmKey == null || realmKey.isBlank()) {
            return null;
        }
        return realmProvider.findByRealmKey(realmKey).map(RealmModel::id).orElse(null);
    }
}
