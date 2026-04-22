package club.beenest.payment.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 钱包管理端查询参数
 *
 * @author System
 * @since 2026-02-11
 */
@Data
@Schema(name = "WalletAdminQueryDTO", description = "钱包管理端查询参数")
public class WalletAdminQueryDTO {

    @Schema(description = "用户编号")
    private String customerNo;

    @Schema(description = "钱包编号")
    private String walletNo;

    @Schema(description = "状态：ACTIVE/FROZEN/CLOSED")
    private String status;

    @Schema(description = "业务类型（多租户隔离）")
    private String bizType;
}

