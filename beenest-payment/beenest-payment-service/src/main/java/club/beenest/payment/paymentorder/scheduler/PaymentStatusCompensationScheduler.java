package club.beenest.payment.paymentorder.scheduler;

import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付状态补偿调度器
 *
 * <p>扫描"已支付但业务系统可能未确认"的订单，重新写入 Outbox 消息，
 * 确保业务系统最终一定能收到支付成功通知。</p>
 *
 * <h3>补偿场景：</h3>
 * <ol>
 *   <li>Outbox Scheduler 发送 MQ 后，MQ 投递到业务系统失败（如队列满、网络分区）</li>
 *   <li>业务系统消费 MQ 后处理失败且未重试成功</li>
 *   <li>Outbox 消息因超过 maxRetry 被标记为 FAILED</li>
 *   <li>极端情况：Outbox 写入后数据库崩溃，Outbox 记录丢失</li>
 * </ol>
 *
 * <h3>工作原理：</h3>
 * <ol>
 *   <li>查询 {@code ds_payment_order} 中 {@code status = 'PAID'} 且更新时间超过 10 分钟的订单</li>
 *   <li>对每笔订单检查 Outbox 中是否已有成功发送记录</li>
 *   <li>如果没有，重新写入 Outbox 消息</li>
 * </ol>
 *
 * <h3>幂等性：</h3>
 * <p>业务系统消费者 {@code PaymentEventConsumer} 已有 Redis 幂等控制，
 * 重复收到相同业务单号的消息不会重复处理。</p>
 *
 * @author System
 * @since 2026-07-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStatusCompensationScheduler {

    private final PaymentOrderMapper paymentOrderMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final PaymentEventProducer paymentEventProducer;

    /** 已支付但可能未被业务确认的订单扫描延迟（分钟） */
    private static final int PAID_UNCONFIRMED_DELAY_MINUTES = 10;

    private static final int BATCH_SIZE = 50;

    /**
     * 每 5 分钟执行一次补偿扫描
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void compensateUnconfirmedPaidOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(PAID_UNCONFIRMED_DELAY_MINUTES);
        List<PaymentOrder> paidOrders;
        try {
            paidOrders = paymentOrderMapper.selectPaidBeforeTime(threshold, BATCH_SIZE);
        } catch (Exception e) {
            log.error("补偿调度：查询已支付订单失败", e);
            return;
        }

        if (paidOrders.isEmpty()) {
            return;
        }

        log.info("补偿调度：扫描到 {} 笔已支付超 {} 分钟的订单", paidOrders.size(), PAID_UNCONFIRMED_DELAY_MINUTES);

        int compensated = 0;
        for (PaymentOrder order : paidOrders) {
            try {
                // 仅处理有 bizNo 的业务订单（充值订单不需要通知业务系统）
                String bizNo = order.getBizNo();
                if (bizNo == null || bizNo.isEmpty()) {
                    continue;
                }

                // 检查 Outbox 是否已有成功发送的 ORDER-COMPLETE 消息
                boolean hasConfirmedOutbox = outboxMessageMapper.existsSentByRoutingKeyAndOrderNo(
                        "payment.order.completed", order.getOrderNo());

                if (hasConfirmedOutbox) {
                    log.debug("补偿调度：订单已有成功Outbox记录，跳过 - orderNo: {}", order.getOrderNo());
                    continue;
                }

                // 重新写入 Outbox 消息
                PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
                msg.setOrderNo(order.getOrderNo());
                msg.setBusinessOrderNo(bizNo);
                msg.setCustomerNo(order.getCustomerNo());
                msg.setAmountFen(order.getAmount());
                msg.setPlatform(order.getPlatform());
                msg.setBizType(order.getBizType());
                msg.setAppId(order.getAppId() != null ? order.getAppId() : BizTypeConstants.deriveAppId(order.getBizType()));
                msg.setPaidAt(order.getPaidTime() != null ? order.getPaidTime().toString() : order.getUpdateTime().toString());

                paymentEventProducer.sendOrderCompletedToOutbox(msg);
                compensated++;
                log.info("补偿调度：已重新写入Outbox - orderNo: {}, bizNo: {}", order.getOrderNo(), bizNo);

            } catch (Exception e) {
                log.error("补偿调度：处理订单失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage(), e);
            }
        }

        if (compensated > 0) {
            log.info("补偿调度完成：本次补偿 {} 笔订单", compensated);
        }
    }
}
