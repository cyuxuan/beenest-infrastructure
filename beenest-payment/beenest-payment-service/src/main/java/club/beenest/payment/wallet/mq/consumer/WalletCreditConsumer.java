package club.beenest.payment.wallet.mq.consumer;

import club.beenest.payment.common.exception.BusinessException;
import club.beenest.payment.shared.mq.MessageSignUtil;
import club.beenest.payment.shared.mq.PaymentMqConstants;
import club.beenest.payment.shared.mq.WalletCreditMessage;
import club.beenest.payment.wallet.domain.enums.WalletTransactionType;
import club.beenest.payment.wallet.service.IWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 钱包入账 MQ 消费者
 * 监听业务系统发送的通用钱包入账指令，执行余额增加操作
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>幂等：通过 referenceNo 唯一约束防止重复入账</li>
 *   <li>验签：HMAC-SHA256 防伪造/篡改</li>
 *   <li>枚举校验：transactionType 必须是 WalletTransactionType 枚举值</li>
 *   <li>重试：消费失败由 RabbitMQ 重试机制 + 死信队列兜底</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletCreditConsumer {

    private final IWalletService walletService;

    @RabbitListener(queues = PaymentMqConstants.QUEUE_WALLET_CREDIT)
    public void onWalletCredit(WalletCreditMessage message) {
        log.info("收到钱包入账指令: customerNo={}, amountFen={}, transactionType={}, referenceNo={}",
                message.getCustomerNo(), message.getAmountFen(),
                message.getTransactionType(), message.getReferenceNo());

        // 1. 验签
        if (!MessageSignUtil.verifyWalletCreditMessage(
                message.getSign(), message.getMessageId(), message.getCustomerNo(),
                message.getBizType(), message.getAmountFen(),
                message.getTransactionType(), message.getReferenceNo())) {
            log.error("【安全告警】钱包入账消息签名验证失败: customerNo={}, referenceNo={}",
                    message.getCustomerNo(), message.getReferenceNo());
            throw new IllegalArgumentException("消息签名验证失败");
        }

        // 2. 参数校验
        validateMessage(message);

        try {
            // 3. 执行入账（幂等：referenceNo 唯一约束防重复）
            BigDecimal amountYuan = new BigDecimal(message.getAmountFen()).divide(new BigDecimal("100"));
            walletService.addBalance(
                    message.getCustomerNo(),
                    message.getBizType(),
                    amountYuan,
                    message.getDescription(),
                    message.getTransactionType(),
                    message.getReferenceNo());

            log.info("钱包入账成功: customerNo={}, amountFen={}, transactionType={}, referenceNo={}",
                    message.getCustomerNo(), message.getAmountFen(),
                    message.getTransactionType(), message.getReferenceNo());

        } catch (BusinessException e) {
            // 幂等保护：如果交易已存在（之前已入账成功），视为成功
            if (e.getMessage() != null && e.getMessage().contains("交易已存在")) {
                log.info("钱包入账幂等命中（交易已存在），视为成功: referenceNo={}", message.getReferenceNo());
                return;
            }
            log.error("钱包入账失败: customerNo={}, referenceNo={}, error={}",
                    message.getCustomerNo(), message.getReferenceNo(), e.getMessage(), e);
            throw e;
        } catch (org.springframework.dao.DuplicateKeyException dke) {
            // 数据库 UNIQUE(reference_no) 约束命中，并发入账已成功
            log.info("钱包入账幂等命中（DuplicateKey），视为成功: referenceNo={}", message.getReferenceNo());
            return;
        } catch (Exception e) {
            log.error("钱包入账失败: customerNo={}, referenceNo={}, error={}",
                    message.getCustomerNo(), message.getReferenceNo(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 参数校验：必填字段 + transactionType 枚举合法性
     */
    private void validateMessage(WalletCreditMessage message) {
        if (StringUtils.isBlank(message.getCustomerNo())) {
            throw new IllegalArgumentException("customerNo 不能为空");
        }
        if (message.getAmountFen() == null || message.getAmountFen() <= 0) {
            throw new IllegalArgumentException("amountFen 必须大于 0");
        }
        if (StringUtils.isBlank(message.getTransactionType())) {
            throw new IllegalArgumentException("transactionType 不能为空");
        }
        if (WalletTransactionType.getByCode(message.getTransactionType()) == null) {
            throw new IllegalArgumentException("非法的 transactionType: " + message.getTransactionType());
        }
        if (StringUtils.isBlank(message.getDescription())) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (StringUtils.isBlank(message.getReferenceNo())) {
            throw new IllegalArgumentException("referenceNo 不能为空");
        }
    }
}
