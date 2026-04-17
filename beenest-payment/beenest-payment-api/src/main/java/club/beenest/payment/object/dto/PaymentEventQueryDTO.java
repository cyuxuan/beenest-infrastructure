package club.beenest.payment.object.dto;

import lombok.Data;

@Data
public class PaymentEventQueryDTO {
    private String orderNo;
    private String eventType;
    private String channel;
}
