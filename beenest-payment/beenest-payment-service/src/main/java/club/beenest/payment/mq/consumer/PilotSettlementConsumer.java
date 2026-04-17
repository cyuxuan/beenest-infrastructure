package club.beenest.payment.mq.consumer;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.mq.MessageSignUtil;
import club.beenest.payment.mq.PaymentMqConstants;
import club.beenest.payment.mq.message.PilotSettlementMessage;
import club.beenest.payment.service.IWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 飞手结算 MQ 消费者
 * 监听 drone-system 通过 Outbox 中转发送的结算指令，执行钱包余额操作
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>幂等：通过 referenceNo（planNo）唯一约束防止重复入账</li>
 *   <li>验签：HMAC-SHA256 防伪造/篡改</li>
 *   <li>重试：消费失败由 RabbitMQ 重试机制 + 死信队列兜底</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PilotSettlementConsumer {

    private final IWalletService walletService;

    private static final String TRANSACTION_TYPE_PILOT_INCOME = "PILOT_INCOME";
    private static final String TRANSACTION_TYPE_PLATFORM_FEE = "PLATFORM_FEE";

    @RabbitListener(queues = PaymentMqConstants.QUEUE_PILOT_SETTLEMENT)
    public void onPilotSettlement(PilotSettlementMessage message) {
        log.info("收到飞手结算消息: planNo={}, pilotUserId={}, pilotIncome={}, platformFee={}",
                message.getPlanNo(), message.getPilotUserId(),
                message.getPilotIncome(), message.getPlatformFee());

        // 验签：防伪造/篡改
        if (!MessageSignUtil.verifyPilotSettlementMessage(
                message.getSign(), message.getMessageId(), message.getPlanNo(),
                message.getPilotUserId(), message.getPilotIncome(),
                message.getPlatformFee(), message.getOrderAmount(), message.getBizType())) {
            log.error("【安全告警】飞手结算消息签名验证失败，疑似伪造: planNo={}", message.getPlanNo());
            throw new IllegalArgumentException("消息签名验证失败");
        }

        // 参数校验
        if (StringUtils.isBlank(message.getPlanNo())) {
            log.warn("飞手结算消息缺少 planNo，跳过: messageId={}", message.getMessageId());
            return;
        }

        if (StringUtils.isBlank(message.getPilotUserId())) {
            log.warn("飞手结算消息缺少 pilotUserId，跳过: planNo={}", message.getPlanNo());
            return;
        }

        try {
            // 1. 飞手收入入账（幂等：referenceNo = planNo 唯一约束防重复）
            BigDecimal pilotIncome = new BigDecimal(message.getPilotIncome());
            walletService.addBalance(
                    message.getPilotUserId(),
                    message.getBizType(),
                    pilotIncome,
                    "飞手收入 - 订单完成结算",
                    TRANSACTION_TYPE_PILOT_INCOME,
                    message.getPlanNo());

            // 2. 平台服务费入账
            if (StringUtils.isNotBlank(message.getPlatformFee())) {
                BigDecimal platformFee = new BigDecimal(message.getPlatformFee());
                if (platformFee.compareTo(BigDecimal.ZERO) > 0) {
                    String platformFeeRef = message.getPlanNo() + "_PF";
                    walletService.addBalance(
                            message.getPilotUserId(),
                            message.getBizType(),
                            platformFee,
                            "平台服务费收入 - 订单: " + message.getPlanNo(),
                            TRANSACTION_TYPE_PLATFORM_FEE,
                            platformFeeRef);
                }
            }

            log.info("飞手结算处理成功: planNo={}, pilotUserId={}, pilotIncome={}, platformFee={}",
                    message.getPlanNo(), message.getPilotUserId(),
                    message.getPilotIncome(), message.getPlatformFee());

        } catch (BusinessException e) {
            // 幂等保护：如果交易已存在（之前已入账成功），视为成功
            if (e.getMessage() != null && e.getMessage().contains("交易已存在")) {
                log.info("飞手结算幂等命中（交易已存在），视为成功: planNo={}", message.getPlanNo());
                return;
            }

            log.error("飞手结算处理失败: planNo={}, error={}", message.getPlanNo(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("飞手结算处理失败: planNo={}, error={}", message.getPlanNo(), e.getMessage(), e);
            throw e;
        }
    }
}
