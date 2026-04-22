package club.beenest.payment.paymentorder.dto;

import lombok.Data;

@Data
public class RefundQueryDTO {
    private String refundNo;
    private String orderNo;
    private String status;
    private String refundPolicy;
    private String requestSource;
    private String applicantId;
    private String channelStatus;
    private String startTime;
    private String endTime;
}
