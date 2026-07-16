package club.beenest.payment.reconciliation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ReconciliationQueryDTO {
    private String date;
    private String channel;
    private String status;

    /** 业务类型标识，用于多租户隔离 */
    private String bizType;

    /** 业务系统标识，由拦截器自动注入 */
    @Schema(description = "业务系统标识，由拦截器自动注入", hidden = true)
    private String appId;
}
