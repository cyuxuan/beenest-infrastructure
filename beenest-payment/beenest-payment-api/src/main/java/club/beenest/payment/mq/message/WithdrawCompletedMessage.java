package club.beenest.payment.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 提现完成消息
 * 支付中台 → 主业务服务
 */
@Data
public class WithdrawCompletedMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提现申请号 */
    private String requestNo;

    /** 用户编号 */
    private String customerNo;

    /** 提现金额（分） */
    private Long amountFen;

    /** 手续费（分） */
    private Long feeFen;

    /** 实际到账金额（分） */
    private Long actualAmountFen;

    /** 提现状态（SUCCESS/FAILED） */
    private String status;

    /** 消息唯一ID（用于幂等） */
    private String messageId;

    /** 业务类型（多租户隔离） */
    private String bizType;

    /** 消息签名（HMAC-SHA256，防伪造/篡改） */
    private String sign;
}
