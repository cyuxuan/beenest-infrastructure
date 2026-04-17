package club.beenest.payment.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 飞手结算消息（反序列化用）
 *
 * <p>主副本已迁移至 drone-common 模块（club.beenest.drone.common.object.dto.PilotSettlementMessage）。</p>
 * <p>此类仅用于 beenest-payment 端 MQ 消息反序列化，字段与主副本保持一致。</p>
 *
 * @author System
 * @since 2026-04-13
 * @deprecated 主副本在 drone-common 中，此类仅为 beenest-payment 反序列化兼容保留
 */
@Data
@Deprecated
public class PilotSettlementMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单计划编号（幂等键） */
    private String planNo;

    /** 飞手用户ID */
    private String pilotUserId;

    /** 飞手收入金额（元） */
    private String pilotIncome;

    /** 平台服务费（元） */
    private String platformFee;

    /** 订单总金额（元） */
    private String orderAmount;

    /** 飞手编号 */
    private String providerNo;

    /** 消息唯一ID（用于幂等防重） */
    private String messageId;

    /** 业务类型 */
    private String bizType;

    /** 消息签名（HMAC-SHA256，防伪造/篡改） */
    private String sign;
}
