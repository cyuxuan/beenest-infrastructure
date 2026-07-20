package club.beenest.payment.payscore.domain.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用授权记录实体类
 * 对应数据库表：ds_credit_authorization
 *
 * <p>记录每次支付分信用免押授权的详细结果，包括信用评分、免押结果、冻结金额等信息。</p>
 *
 * <h3>免押结果：</h3>
 * <ul>
 *   <li>FULL_EXEMPT - 完全免押（信用分达标，无需缴纳保证金）</li>
 *   <li>PARTIAL_EXEMPT - 部分免押（信用分较高，仅需缴纳部分保证金）</li>
 *   <li>NOT_EXEMPT - 不满足免押（信用分不足，需全额缴纳保证金）</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "CreditAuthorization", description = "信用授权记录实体")
@Data
public class CreditAuthorization {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "授权编号", example = "AUTH202606151234567890123")
    private String authNo;

    @Schema(description = "关联服务订单号")
    private String orderNo;

    @Schema(description = "用户/商户编号")
    private String customerNo;

    @Schema(description = "支付分平台", example = "WECHAT_PAYSCORE")
    private String platform;

    @Schema(description = "用户信用分", example = "750")
    private Integer creditScore;

    @Schema(description = "保证金金额（分）", example = "100000")
    private Long depositAmount;

    @Schema(description = "免押结果", example = "FULL_EXEMPT",
            allowableValues = {"FULL_EXEMPT", "PARTIAL_EXEMPT", "NOT_EXEMPT"})
    private String exemptionResult;

    @Schema(description = "实际冻结金额（分）", example = "100000")
    private Long frozenAmount;

    @Schema(description = "授权状态", example = "PENDING")
    private String authStatus;

    @Schema(description = "授权时间")
    private LocalDateTime authTime;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "第三方授权编号")
    private String thirdPartyAuthNo;

    @Schema(description = "业务系统标识（DRONE/SHOP），用于多租户隔离", example = "DRONE")
    private String appId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ==================== 领域方法 ====================

    /**
     * 检查是否完全免押
     *
     * @return true表示完全免押
     */
    public boolean isFullExempt() {
        return "FULL_EXEMPT".equals(exemptionResult);
    }

    /**
     * 检查是否部分免押
     *
     * @return true表示部分免押
     */
    public boolean isPartialExempt() {
        return "PARTIAL_EXEMPT".equals(exemptionResult);
    }

    /**
     * 检查是否不满足免押
     *
     * @return true表示不满足免押条件
     */
    public boolean isNotExempt() {
        return "NOT_EXEMPT".equals(exemptionResult);
    }

    /**
     * 标记为已授权
     *
     * @param creditScore 信用分
     * @param frozenAmount 冻结金额
     * @param thirdPartyAuthNo 第三方授权编号
     */
    public void markAsAuthorized(Integer creditScore, Long frozenAmount, String thirdPartyAuthNo) {
        this.authStatus = "AUTHORIZED";
        this.creditScore = creditScore;
        this.frozenAmount = frozenAmount;
        this.thirdPartyAuthNo = thirdPartyAuthNo;
        this.authTime = LocalDateTime.now();
    }

    /**
     * 标记为授权失败
     */
    public void markAsFailed() {
        this.authStatus = "FAILED";
    }
}
