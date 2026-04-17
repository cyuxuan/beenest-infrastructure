package club.beenest.payment.object.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账结果（双边比对后）
 *
 * @author System
 * @since 2026-04-08
 */
@Data
public class ReconciliationResultDTO {

    /**
     * 本地订单数
     */
    private int localCount;

    /**
     * 本地已支付金额（分）
     */
    private long localAmount;

    /**
     * 平台账单笔数
     */
    private int platformCount;

    /**
     * 平台账单金额（分）
     */
    private long platformAmount;

    /**
     * 匹配笔数
     */
    private int matchCount;

    /**
     * 不匹配笔数
     */
    private int mismatchCount;

    /**
     * 不匹配明细列表
     */
    private List<ReconciliationDetailDTO> details = new ArrayList<>();
}
