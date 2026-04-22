package club.beenest.payment.shared.domain.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 风控规则实体
 *
 * @author System
 * @since 2026-02-11
 */
@Data
public class RiskRule {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 规则代码
     */
    private String ruleCode;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 规则类型 (LIMIT, FREQUENCY, BLACKLIST)
     */
    private String ruleType;

    /**
     * 阈值
     */
    private Long threshold;

    /**
     * 时间窗口（秒）
     */
    private Integer timeWindow;

    /**
     * 触发动作 (REJECT, REVIEW, ALERT)
     */
    private String action;

    /**
     * 是否启用
     */
    private Boolean isEnable;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
