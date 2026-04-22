package club.beenest.payment.wallet.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钱包完整性校验日志实体
 * 对应数据库表：ds_wallet_integrity_log
 *
 * @author System
 * @since 2026-04-01
 */
@Data
public class WalletIntegrityLog {

    private Long id;
    private String walletNo;
    private String customerNo;
    private Long expectedBalance;
    private Long actualBalance;
    private Long expectedFrozeBalance;
    private Long actualFrozeBalance;
    private Boolean hashValid;
    private String status;
    private String detail;
    private LocalDateTime createTime;
}
