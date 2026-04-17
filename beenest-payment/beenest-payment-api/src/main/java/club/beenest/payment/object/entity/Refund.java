package club.beenest.payment.object.entity;

import club.beenest.payment.object.enums.RefundStatus;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 退款记录实体
 */
@Data
public class Refund {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 退款单号
     */
    private String refundNo;

    /**
     * 原订单号
     */
    private String orderNo;

    /**
     * 退款金额（分）
     */
    private Long amount;

    /**
     * 退款原因
     */
    private String reason;

    /**
     * 退款状态 (PENDING, SUCCESS, FAILED)
     */
    private String status;

    /**
     * 退款策略（AUTO_REFUND/MANUAL_REVIEW/NOT_ALLOWED）
     */
    private String refundPolicy;

    /**
     * 申请来源（CUSTOMER_CANCEL/ADMIN/AUTO）
     */
    private String requestSource;

    /**
     * 申请人
     */
    private String applicantId;

    /**
     * 第三方退款单号
     */
    private String thirdPartyRefundNo;

    /**
     * 渠道退款状态
     */
    private String channelStatus;

    /**
     * 审核人
     */
    private String auditUser;

    /**
     * 审核时间
     */
    private LocalDateTime auditTime;

    /**
     * 审核备注
     */
    private String auditRemark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 客户姓名
     */
    private String customerName;

    /**
     * 客户手机号
     */
    private String customerPhone;

    /**
     * 客户编号
     */
    private String customerNo;

    public RefundStatus getStatusEnum() {
        return RefundStatus.getByCode(status);
    }

    public void setStatusEnum(RefundStatus statusEnum) {
        if (statusEnum != null) {
            this.status = statusEnum.getCode();
        }
    }
}
