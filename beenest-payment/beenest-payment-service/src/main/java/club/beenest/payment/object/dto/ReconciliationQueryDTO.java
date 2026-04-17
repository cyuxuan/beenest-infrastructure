package club.beenest.payment.object.dto;

import lombok.Data;

@Data
public class ReconciliationQueryDTO {
    private String date;
    private String channel;
    private String status;
}
