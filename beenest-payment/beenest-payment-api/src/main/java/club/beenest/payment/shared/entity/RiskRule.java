package club.beenest.payment.shared.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
     * 主键ID（更新时必填）
     */
    private Long id;

    /**
     * 规则代码
     */
    @NotBlank(message = "规则代码不能为空")
    private String ruleCode;

    /**
     * 规则名称
     */
    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    /**
     * 规则类型 (LIMIT, FREQUENCY, BLACKLIST)
     */
    @NotBlank(message = "规则类型不能为空")
    private String ruleType;

    /**
     * 阈值
     */
    @NotNull(message = "阈值不能为空")
    @Positive(message = "阈值必须大于0")
    private Long threshold;

    /**
     * 时间窗口（秒）
     */
    private Integer timeWindow;

    /**
     * 触发动作 (REJECT, REVIEW, ALERT)
     */
    @NotBlank(message = "触发动作不能为空")
    private String action;

    /**
     * 是否启用
     */
    @NotNull(message = "启用状态不能为空")
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
