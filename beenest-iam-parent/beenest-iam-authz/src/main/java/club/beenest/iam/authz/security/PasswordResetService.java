package club.beenest.iam.authz.security;

import club.beenest.iam.authz.notification.NotificationService;
import club.beenest.iam.authz.sms.SmsCodeService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Year;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 自助找回口令服务。
 * <p>分申请与确认两步,两步之间只靠一枚随机票据关联;票据一次性消费、限时有效,
 * 链接中不含用户标识。</p>
 * <p>申请结果不泄露账号存在性:账号不存在、无可用找回方式、投递失败均返回同一结果,
 * 差异只进投递记录与日志。</p>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** 票据字节数(编码后约 43 字符) */
    private static final int TICKET_BYTES = 32;
    /** 同一账号两次申请的最小间隔 */
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    /** 同一账号每小时最多申请次数 */
    private static final int MAX_PER_HOUR = 5;
    /** IP 维度限流窗口 */
    private static final Duration IP_WINDOW = Duration.ofMinutes(10);
    /** IP 维度窗口内最大申请次数 */
    private static final int IP_LIMIT = 5;
    /** 票据默认有效期(分钟) */
    private static final int DEFAULT_TTL_MINUTES = 30;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserProvider userProvider;
    private final RealmProvider realmProvider;
    private final PasswordChangeService passwordChangeService;
    private final NotificationService notificationService;
    private final SmsCodeService smsCodeService;
    private final IamNotificationLogManager notificationLogManager;
    private final RedisUtils redisUtils;
    private final IamRateLimiter rateLimiter;
    private final int ttlMinutes;

    /**
     * 构造器注入。
     *
     * @param ttlMinutes 票据有效期(分钟);非正数时回落到 30 分钟
     */
    public PasswordResetService(UserProvider userProvider, RealmProvider realmProvider,
                                PasswordChangeService passwordChangeService,
                                NotificationService notificationService,
                                SmsCodeService smsCodeService,
                                IamNotificationLogManager notificationLogManager,
                                RedisUtils redisUtils, IamRateLimiter rateLimiter,
                                @Value("${iam.account.reset-ttl-minutes:30}") int ttlMinutes) {
        this.userProvider = userProvider;
        this.realmProvider = realmProvider;
        this.passwordChangeService = passwordChangeService;
        this.notificationService = notificationService;
        this.smsCodeService = smsCodeService;
        this.notificationLogManager = notificationLogManager;
        this.redisUtils = redisUtils;
        this.rateLimiter = rateLimiter;
        this.ttlMinutes = ttlMinutes > 0 ? ttlMinutes : DEFAULT_TTL_MINUTES;
    }

    /**
     * 申请找回口令。
     * <p>账号含 {@code @} 时按邮箱处理,其余按手机号;手机号路径复用短信验证码通道,
     * 邮件路径要求邮件通道可用。</p>
     *
     * @param realmKey 身份域标识
     * @param account  邮箱或手机号
     * @param ip       来源 IP
     * @param baseUrl  对外基础地址(用于拼接链接,如 {@code https://iam.example.com})
     * @throws BusinessException 来源 IP 超过限流阈值
     */
    public void requestReset(String realmKey, String account, String ip, String baseUrl) {
        // 1. IP 维度限流:挡分布式批量探测
        rateLimiter.check("pwreset-ip", ip == null ? "unknown" : ip, IP_LIMIT, IP_WINDOW);
        if (account == null || account.isBlank()) {
            return;
        }
        RealmModel realm = realmProvider.findByRealmKey(realmKey).orElse(null);
        if (realm == null) {
            return;
        }
        String subject = account.trim();
        boolean emailChannel = subject.contains("@");
        // 2. 定位账号:找不到就静默返回,不给出任何可区分的响应
        UserModel user = emailChannel
                ? userProvider.findByEmail(realm.id(), subject).orElse(null)
                : userProvider.findByPhone(realm.id(), subject).orElse(null);
        if (user == null) {
            log.debug("找回口令:账号不存在,静默返回 realm={}", realmKey);
            return;
        }
        // 3. 账号维度节流:同一账号反复申请与"账号不存在"必须同形,命中时静默返回
        if (throttled(realm.id(), subject)) {
            log.info("找回口令:申请过于频繁,跳过 realm={}, userId={}", realmKey, user.id());
            return;
        }
        // 4. 按渠道投递
        if (emailChannel) {
            sendResetMail(realm, user, subject, baseUrl);
        } else {
            sendResetSms(realm, user, subject, ip);
        }
    }

    /**
     * 用票据设置新口令。
     *
     * @param realmKey    身份域标识
     * @param ticket      票据
     * @param newPassword 新口令明文
     * @throws BusinessException 票据无效、身份域不存在或口令不符合域策略
     */
    public void confirmReset(String realmKey, String ticket, String newPassword) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID);
        }
        // GETDEL 原子消费:并发提交时只有一次能拿到票据,重放拿到 null
        String raw = redisUtils.getAndDelete(
                String.format(IamRedisKeyConstants.PASSWORD_RESET_TICKET, realmKey, ticket.trim()));
        if (raw == null) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID);
        }
        Long userId = parseUserId(raw);
        if (userId == null) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID);
        }
        // 改密走统一入口:策略校验、凭证轮换、口令历史、令牌版本递增一并完成;
        // 口令既已由用户自己设定,"必须改密"待办随之满足
        RealmModel realm = realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey));
        passwordChangeService.changePassword(realm.id(), userId, newPassword);
        log.info("口令已通过找回链接重置: realm={}, userId={}", realmKey, userId);
    }

    /**
     * 邮件路径:签发票据并发送重置链接。
     *
     * @param realm   身份域
     * @param user    用户
     * @param email   收件邮箱
     * @param baseUrl 对外基础地址
     */
    private void sendResetMail(RealmModel realm, UserModel user, String email, String baseUrl) {
        if (!notificationService.mailAvailable(realm.id())) {
            log.info("找回口令:邮件通道未配置,跳过 realm={}", realm.realmKey());
            return;
        }
        String ticket = issueTicket(realm.realmKey(), user.id());
        String resetUrl = trimTrailingSlash(baseUrl) + "/reset-password?realm="
                + realm.realmKey() + "&ticket=" + ticket;
        Map<String, Object> variables = new HashMap<>();
        variables.put("username", displayName(user));
        variables.put("resetUrl", resetUrl);
        variables.put("ttlMinutes", ttlMinutes);
        variables.put("appName", realm.realmName());
        variables.put("year", Year.now().getValue());
        notificationService.sendMail(realm.id(), realm.realmKey(), user.id(), email,
                NotificationService.TEMPLATE_PASSWORD_RESET,
                "重置你的 " + realm.realmName() + " 口令", variables);
    }

    /**
     * 短信路径:复用短信验证码通道,用户凭码在重置页设置新口令;投递失败只记录。
     *
     * @param realm 身份域
     * @param user  用户
     * @param phone 手机号
     * @param ip    来源地址,用于发送限流;可为空
     */
    private void sendResetSms(RealmModel realm, UserModel user, String phone, String ip) {
        try {
            smsCodeService.sendCode(realm.realmKey(), phone, ip);
        } catch (RuntimeException e) {
            // 与邮件路径同一原则:失败不外露,只记录
            log.warn("找回口令:短信投递失败 realm={}, userId={}, error={}",
                    realm.realmKey(), user.id(), e.getMessage());
        }
    }

    /**
     * 签发一次性票据,值为 {@code userId}。
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @return 票据
     */
    private String issueTicket(String realmKey, Long userId) {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisUtils.set(String.format(IamRedisKeyConstants.PASSWORD_RESET_TICKET, realmKey, ticket),
                String.valueOf(userId), Duration.ofMinutes(ttlMinutes));
        return ticket;
    }

    /**
     * 账号维度节流:60 秒内重复申请或一小时超限均视为节流命中。
     *
     * @param realmId 身份域主键
     * @param subject 邮箱或手机号
     * @return 是否命中节流
     */
    private boolean throttled(Long realmId, String subject) {
        String key = String.format(IamRedisKeyConstants.PASSWORD_RESET_REQUEST, realmId, subject);
        // 首次申请写入标记;已存在说明 60 秒内来过一次
        boolean firstInWindow = redisUtils.setIfAbsent(key, "1", RESEND_INTERVAL);
        if (!firstInWindow) {
            return true;
        }
        int recent = notificationLogManager.countRecent(realmId, subject, 60);
        return recent > MAX_PER_HOUR;
    }

    /**
     * 把票据值解析为用户主键。
     *
     * @param raw 票据值
     * @return 用户主键;格式非法时返回 null
     */
    private static Long parseUserId(String raw) {
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取展示名:昵称优先,为空时回落登录名。
     *
     * @param user 用户
     * @return 展示名
     */
    private static String displayName(UserModel user) {
        String nickname = user.nickname();
        return nickname == null || nickname.isBlank() ? user.username() : nickname;
    }

    /**
     * 去掉基础地址末尾的斜杠。
     *
     * @param baseUrl 对外基础地址
     * @return 处理后的地址;入参为空时返回空串
     */
    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 账号脱敏展示:邮箱保留首字符与域名,手机号保留后四位。
     *
     * @param account 邮箱或手机号
     * @return 脱敏结果;入参为空时返回空串
     */
    public static String maskedAccount(String account) {
        if (account == null || account.isBlank()) {
            return "";
        }
        String value = account.trim().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
    }
}
