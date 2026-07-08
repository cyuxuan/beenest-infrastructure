package club.beenest.payment.reconciliation.dto;

import lombok.Data;

@Data
public class ReconciliationQueryDTO {
    private String date;
    private String channel;
    private String status;

    /** 业务类型标识，用于多租户隔离 */
    private String bizType;
}
