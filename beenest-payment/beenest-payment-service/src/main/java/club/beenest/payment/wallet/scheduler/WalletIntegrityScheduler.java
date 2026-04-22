package club.beenest.payment.wallet.scheduler;

import club.beenest.payment.wallet.service.IWalletIntegrityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 钱包完整性定时校验任务
 * 每日凌晨2点自动执行全量对账
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletIntegrityScheduler {

    private final IWalletIntegrityService walletIntegrityService;

    /**
     * 每日凌晨2:13执行全量钱包完整性校验
     */
    @Scheduled(cron = "0 13 2 * * ?")
    public void dailyIntegrityCheck() {
        log.info("开始定时钱包完整性校验");
        try {
            Map<String, Object> result = walletIntegrityService.checkAllWallets();
            long failCount = result.get("failCount") != null ? ((Number) result.get("failCount")).longValue() : 0;
            if (failCount > 0) {
                log.error("【安全告警】定时校验发现{}个异常钱包", failCount);
            } else {
                log.info("定时校验通过，所有钱包数据一致");
            }
        } catch (Exception e) {
            log.error("定时钱包完整性校验异常", e);
        }
    }
}
