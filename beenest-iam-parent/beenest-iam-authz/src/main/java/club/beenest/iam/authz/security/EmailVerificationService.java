package club.beenest.iam.authz.security;

import club.beenest.iam.authz.notification.NotificationService;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Year;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 邮箱验证服务。
 * <p>为未验证的邮箱签发一次性票据并发送验证链接,票据有效期默认 60 分钟;
 * 确认后置邮箱为已验证,并摘除 {@code VERIFY_EMAIL} 待办。</p>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** 票据字节数 */
    private static final int TICKET_BYTES = 32;
    /** 票据有效期(分钟) */
    private static final int DEFAULT_TTL_MINUTES = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserProvider userProvider;
    private final RealmProvider realmProvider;
    private final IamUserManager userManager;
    private final RequiredActionService requiredActionService;
    private final NotificationService notificationService;
    private final RedisUtils redisUtils;
    private final int ttlMinutes;

    /**
     * 构造器注入。
     *
     * @param ttlMinutes 票据有效期(分钟);非正数时回落到 60 分钟
     */
    public EmailVerificationService(UserProvider userProvider, RealmProvider realmProvider,
                                    IamUserManager userManager,
                                    RequiredActionService requiredActionService,
                                    NotificationService notificationService,
                                    RedisUtils redisUtils,
                                    @Value("${iam.account.email-verify-ttl-minutes:60}") int ttlMinutes) {
        this.userProvider = userProvider;
        this.realmProvider = realmProvider;
        this.userManager = userManager;
        this.requiredActionService = requiredActionService;
        this.notificationService = notificationService;
        this.redisUtils = redisUtils;
        this.ttlMinutes = ttlMinutes > 0 ? ttlMinutes : DEFAULT_TTL_MINUTES;
    }

    /**
     * 给用户当前邮箱发送验证链接。
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @param baseUrl  对外基础地址
     * @return 是否已发出
     * @throws BusinessException 账号未填写邮箱、身份域不存在或邮件通道未配置
     */
    public boolean sendVerification(String realmKey, Long userId, String baseUrl) {
        UserModel user = userProvider.findById(userId).orElse(null);
        if (user == null || user.email() == null || user.email().isBlank()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "账号未填写邮箱,无法发送验证邮件");
        }
        if (user.emailVerified()) {
            return false;
        }
        RealmModel realm = realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey));
        if (!notificationService.mailAvailable(realm.id())) {
            throw new BusinessException(ErrorCode.MAIL_CHANNEL_NOT_CONFIGURED);
        }
        String ticket = issueTicket(realmKey, userId);
        Map<String, Object> variables = new HashMap<>();
        variables.put("username", user.nickname() == null || user.nickname().isBlank()
                ? user.username() : user.nickname());
        variables.put("verifyUrl", trimTrailingSlash(baseUrl) + "/verify-email?realm="
                + realmKey + "&ticket=" + ticket);
        variables.put("ttlMinutes", ttlMinutes);
        variables.put("appName", realm.realmName());
        variables.put("year", Year.now().getValue());
        return notificationService.sendMail(realm.id(), realmKey, userId, user.email(),
                NotificationService.TEMPLATE_EMAIL_VERIFICATION,
                "验证你的 " + realm.realmName() + " 邮箱", variables);
    }

    /**
     * 消费验证票据:置邮箱已验证并摘除待办。
     *
     * @param realmKey 身份域标识
     * @param ticket   票据
     * @return 被验证的用户主键
     * @throws BusinessException 票据为空、无效或已过期
     */
    @Transactional
    public Long confirm(String realmKey, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID, "验证链接无效或已过期");
        }
        String raw = redisUtils.getAndDelete(
                String.format(IamRedisKeyConstants.EMAIL_VERIFY_TICKET, realmKey, ticket.trim()));
        if (raw == null) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID, "验证链接无效或已过期");
        }
        Long userId;
        try {
            userId = Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.RESET_TICKET_INVALID, "验证链接无效或已过期");
        }
        userManager.markEmailVerified(userId);
        requiredActionService.complete(null, userId, RequiredAction.VERIFY_EMAIL);
        log.info("邮箱已验证: realm={}, userId={}", realmKey, userId);
        return userId;
    }

    /**
     * 签发票据,值为 {@code userId}。
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @return 票据
     */
    private String issueTicket(String realmKey, Long userId) {
        byte[] bytes = new byte[TICKET_BYTES];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisUtils.set(String.format(IamRedisKeyConstants.EMAIL_VERIFY_TICKET, realmKey, ticket),
                String.valueOf(userId), Duration.ofMinutes(ttlMinutes));
        return ticket;
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
}
