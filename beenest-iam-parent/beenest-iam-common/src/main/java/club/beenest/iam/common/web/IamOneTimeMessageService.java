package club.beenest.iam.common.web;

import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 一次性消息票据服务。
 * <p>消息正文存于 Redis,URL 中只携带一次性票据,取走即失效。
 * 用于登录失败原因等不应出现在地址栏的内容,多实例部署下仍可取回。</p>
 */
@Service
public class IamOneTimeMessageService {

    /** 票据有效期:覆盖产生方到消费方之间的间隔即可 */
    private static final Duration TICKET_TTL = Duration.ofMinutes(2);
    /** 票据字节数(编码后约 32 字符) */
    private static final int TICKET_BYTES = 24;
    /** 随机源 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RedisUtils redisUtils;

    public IamOneTimeMessageService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 暂存消息并签发一次性票据。
     *
     * @param namespace 业务命名空间(不同用途互不串味)
     * @param message   消息正文
     * @return 票据;消息为空时返回 null
     */
    public String issue(String namespace, String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        byte[] bytes = new byte[TICKET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisUtils.set(key(namespace, ticket), message, TICKET_TTL);
        return ticket;
    }

    /**
     * 取回并作废票据对应的消息。
     *
     * @param namespace 业务命名空间
     * @param ticket    票据
     * @return 消息;票据不存在或已过期返回 null
     */
    public String consume(String namespace, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        // 一次性消费:取走即失效,重复访问不会重复显示同一条消息
        return redisUtils.getAndDelete(key(namespace, ticket));
    }

    /**
     * 票据在 Redis 中的键。
     *
     * @param namespace 业务命名空间
     * @param ticket    票据
     * @return Redis 键
     */
    private static String key(String namespace, String ticket) {
        return "iam:msg:" + namespace + ":" + ticket;
    }
}
