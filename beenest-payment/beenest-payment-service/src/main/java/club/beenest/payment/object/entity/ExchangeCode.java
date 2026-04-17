package club.beenest.payment.object.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 兑换码实体
 *
 * @author System
 * @since 2026-04-14
 */
@Data
public class ExchangeCode {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 兑换码
     */
    private String code;

    /**
     * 关联优惠券编号
     */
    private String couponNo;

    /**
     * 状态
     */
    private Integer status;

    /**
     * 使用者ID
     */
    private String usedBy;

    /**
     * 使用时间
     */
    private LocalDateTime usedAt;

    /**
     * 过期时间
     */
    private LocalDateTime expireAt;

    /**
     * 批次号
     */
    private String batchNo;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建者
     */
    private String createBy;
}
