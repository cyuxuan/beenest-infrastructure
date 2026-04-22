package club.beenest.payment.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 交易记录查询参数
 *
 * @author System
 * @since 2026-02-11
 */
@Data
@Schema(name = "TransactionQueryDTO", description = "交易记录查询参数")
public class TransactionQueryDTO {

    @Schema(description = "交易流水号")
    private String transactionNo;

    @Schema(description = "交易类型")
    private String type;

    @Schema(description = "开始时间")
    private String startTime;

    @Schema(description = "结束时间")
    private String endTime;
}
