package club.beenest.payment.reconciliation.dto;

import lombok.Data;

@Data
public class ReconciliationQueryDTO {
    private String date;
    private String channel;
    private String status;
}
