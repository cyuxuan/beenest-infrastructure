package club.beenest.payment.paymentorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 批量同步结果 DTO
 * 替代 syncProcessingRefunds 方法中的 Map 返回值。
 *
 * @author System
 * @since 2026-04-22
 */
@Schema(name = "BatchSyncResultDTO", description = "批量同步结果")
@Data
@Accessors(chain = true)
public class BatchSyncResultDTO {

    @Schema(description = "请求同步数量")
    private int requested;

    @Schema(description = "实际扫描数量")
    private int scanned;

    @Schema(description = "同步成功数量")
    private int success;

    @Schema(description = "同步失败数量")
    private int failed;
}
