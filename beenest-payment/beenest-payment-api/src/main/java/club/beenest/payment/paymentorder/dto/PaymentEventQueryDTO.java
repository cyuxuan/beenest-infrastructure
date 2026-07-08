package club.beenest.payment.paymentorder.dto;

import lombok.Data;

@Data
public class PaymentEventQueryDTO {
    private String orderNo;
    private String eventType;
    private String channel;

    /** 业务类型标识，用于多租户隔离 */
    private String bizType;
}
