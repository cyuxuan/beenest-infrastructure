package club.beenest.payment.shared.mq;

import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.service.AppCredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付中台 RabbitMQ 配置
 *
 * <p>多租户隔离设计：</p>
 * <ul>
 *   <li>Exchange 类型为 Topic，路由键带 appId 后缀实现租户物理隔离</li>
 *   <li>启动时遍历 ds_app_credential 中所有 ACTIVE 租户，自动声明队列/DLQ/绑定</li>
 *   <li>新增租户只需 INSERT ds_app_credential，支付中台自动声明队列，零代码改动</li>
 *   <li>消费方通过 {@link PaymentMqConstants#tenantQueueName(String, String)} 推导队列名，无需硬编码</li>
 * </ul>
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>所有业务队列绑定死信交换机，消费失败的消息进入死信队列</li>
 *   <li>死信队列消息由人工处理或补偿任务定期扫描</li>
 *   <li>消息消费端配合 spring.rabbitmq.listener.simple.retry 实现退避重试</li>
 * </ul>
 */
@Slf4j
@Configuration
public class PaymentMqConfig {

    // ==================== 死信常量 ====================

    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "payment.exchange.dlx";
    /** 死信路由键前缀 */
    private static final String DLX_ROUTING_KEY_PREFIX = "dlx.";

    private final AppCredentialService appCredentialService;

    public PaymentMqConfig(AppCredentialService appCredentialService) {
        this.appCredentialService = appCredentialService;
    }

    // ==================== Exchange ====================

    /**
     * 支付事件交换机（Topic 类型）
     *
     * <p>Topic Exchange 支持通配符路由键匹配，实现租户物理隔离：
     * payment.order.completed.DRONE → payment.order.completed.drone.queue</p>
     */
    @Bean
    public TopicExchange paymentExchange() {
        return ExchangeBuilder.topicExchange(PaymentMqConstants.PAYMENT_EXCHANGE)
                .durable(true)
                .build();
    }

    /** 死信交换机 */
    @Bean
    public DirectExchange paymentDlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    // ==================== 动态租户队列声明 ====================

    /**
     * 动态声明所有租户的队列、DLQ 和绑定
     *
     * <p>启动时遍历 {@link AppCredentialService#getAllActive()} 中所有 ACTIVE 租户，
     * 为每个租户声明 6 个业务队列 + 6 个 DLQ + 12 个 Binding。</p>
     *
     * <p>新增租户流程：INSERT ds_app_credential → 缓存刷新（60s）→ 调用管理 API 热加载队列声明</p>
     */
    @Bean
    public Declarables tenantQueues(TopicExchange paymentExchange, DirectExchange paymentDlxExchange) {
        List<Declarable> declarables = new ArrayList<>();

        for (AppCredential credential : appCredentialService.getAllActive()) {
            String appId = credential.getAppId();
            for (String baseRoutingKey : PaymentMqConstants.ALL_ROUTING_KEYS) {
                declarables.addAll(declareTenantQueue(appId, baseRoutingKey, paymentExchange, paymentDlxExchange));
            }
        }

        logDeclarationSummary(declarables);
        return new Declarables(declarables);
    }

    /**
     * 为单个租户声明一个事件类型的队列 + DLQ + 绑定
     *
     * @param appId          业务系统标识
     * @param baseRoutingKey 基础路由键（如 payment.order.completed）
     * @param exchange       Topic Exchange
     * @param dlxExchange    DLX Exchange
     * @return 声明对象列表（DLQ + Queue + 2 Binding）
     */
    private List<Declarable> declareTenantQueue(String appId, String baseRoutingKey,
                                                 TopicExchange exchange, DirectExchange dlxExchange) {
        // 路由键：payment.order.completed.DRONE
        String routingKey = PaymentMqConstants.tenantRoutingKey(baseRoutingKey, appId);
        // 队列名：payment.order.completed.drone.queue
        String queueName = PaymentMqConstants.tenantQueueName(
                PaymentMqConstants.routingKeyToQueueName(baseRoutingKey), appId);
        // DLQ 名：payment.order.completed.drone.dlq
        String dlqName = PaymentMqConstants.tenantDlqName(
                PaymentMqConstants.routingKeyToDlqName(baseRoutingKey), appId);
        // DLX 路由键：dlx.order.completed.DRONE
        String dlxRoutingKey = PaymentMqConstants.tenantDlxRoutingKey(
                DLX_ROUTING_KEY_PREFIX + PaymentMqConstants.extractDlxSegment(baseRoutingKey), appId);

        // DLQ
        Queue dlq = QueueBuilder.durable(dlqName).build();

        // 业务队列（带死信配置）
        Queue queue = QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", dlxRoutingKey)
                .build();

        // 绑定：业务队列 ← Topic Exchange ← 租户路由键
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);

        // 绑定：DLQ ← DLX Exchange ← DLX 路由键
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlxExchange).with(dlxRoutingKey);

        return List.of(dlq, queue, binding, dlqBinding);
    }

    /**
     * 提供所有租户的 wallet.credit 队列名列表，供 WalletCreditConsumer 监听
     */
    @Bean
    public List<String> walletCreditQueueNames() {
        List<String> queueNames = new ArrayList<>();
        for (AppCredential credential : appCredentialService.getAllActive()) {
            queueNames.add(PaymentMqConstants.tenantQueueName(
                    PaymentMqConstants.QUEUE_WALLET_CREDIT, credential.getAppId()));
        }
        return queueNames;
    }

    private void logDeclarationSummary(List<Declarable> declarables) {
        long queueCount = declarables.stream().filter(Queue.class::isInstance).count();
        long bindingCount = declarables.stream().filter(Binding.class::isInstance).count();
        int tenantCount = appCredentialService.getAllActive().size();
        log.info("声明租户队列: {} 个租户, {} 个队列, {} 个绑定", tenantCount, queueCount, bindingCount);
    }
}
