package club.beenest.payment.paymentorder.dto;

import lombok.Data;

@Data
public class PaymentEventQueryDTO {
    private String orderNo;
    private String eventType;
    private String channel;

    /** 业务类型标识，用于多租户隔离，必须属于当前 appId */
    private String bizType;

    /** 业务系统标识，由拦截器自动注入 */
    private String appId;
}
