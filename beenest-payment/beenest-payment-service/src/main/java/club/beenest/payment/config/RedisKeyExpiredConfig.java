package club.beenest.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;

/**
 * Redis Key过期事件监听配置
 *
 * <p>启用Redis Keyspace Notifications中的过期事件（Ex），
 * 用于监听支付订单过期Key的自动删除事件，触发订单取消逻辑。</p>
 *
 * <h3>前置条件：</h3>
 * <p>Redis需要开启过期事件通知，在redis.conf中配置：</p>
 * <pre>notify-keyspace-events Ex</pre>
 * <p>或通过命令动态开启：</p>
 * <pre>CONFIG SET notify-keyspace-events Ex</pre>
 *
 * @author System
 * @since 2026-03-04
 */
@Configuration
public class RedisKeyExpiredConfig {

    /**
     * 配置Redis消息监听容器
     * 监听db0上所有Key的expired事件
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(@NonNull RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
