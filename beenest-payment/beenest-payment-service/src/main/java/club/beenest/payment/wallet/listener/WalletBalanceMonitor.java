package club.beenest.payment.wallet.listener;

import club.beenest.payment.wallet.event.WalletBalanceChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 钱包余额变动监控
 * 监听余额变动事件，检查异常模式并记录告警
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
@Component
public class WalletBalanceMonitor {

    /**
     * 单次变动告警阈值：100万分 = 10000元
     */
    private static final long LARGE_AMOUNT_THRESHOLD = 1_000_000_00L;

    @EventListener
    public void onBalanceChanged(WalletBalanceChangedEvent event) {
        // 规则1：余额为负（理论上不应发生，由数据库约束兜底）
        if (event.getAfterBalance() < 0) {
            log.error("【安全告警】余额变为负数！walletNo={}, customerNo={}, afterBalance={}",
                    event.getWalletNo(), event.getCustomerNo(), event.getAfterBalance());
        }

        // 规则2：单次大额变动
        long absChange = Math.abs(event.getChangeAmount());
        if (absChange > LARGE_AMOUNT_THRESHOLD) {
            log.warn("【安全告警】大额余额变动 walletNo={}, customerNo={}, changeAmount={}分({}元), type={}",
                    event.getWalletNo(), event.getCustomerNo(),
                    event.getChangeAmount(), absChange / 100.0, event.getTransactionType());
        }
    }
}
