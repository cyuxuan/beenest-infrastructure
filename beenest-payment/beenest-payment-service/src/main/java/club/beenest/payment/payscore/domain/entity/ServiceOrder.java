package club.beenest.payment.payscore.domain.entity;

import club.beenest.payment.payscore.domain.enums.ServiceOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 服务订单实体类
 * 对应数据库表：ds_service_order
 *
 * <p>记录支付分信用免押服务订单信息，包含完整的授权→冻结→服务→完结生命周期。</p>
 *
 * <h3>核心业务规则：</h3>
 * <ul>
 *   <li>actual_amount = 0 表示全额解冻（保证金免扣，服务期结束无费用）</li>
 *   <li>actual_amount > 0 表示部分扣款（实际产生费用）+ 解冻剩余额度</li>
 *   <li>actual_amount 不能超过 frozen_amount</li>
 * </ul>
 *
 * @author System
 * @since 2026-06-15
 */
@Schema(name = "ServiceOrder", description = "支付分服务订单实体")
@Data
public class ServiceOrder {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "服务订单号", example = "S202606151234567890123")
    private String orderNo;

    @Schema(description = "关联业务单号（入驻申请编号）", example = "MCH202606151234567890")
    private String bizNo;

    @Schema(description = "业务类型", example = "MERCHANT_DEPOSIT")
    private String bizType;

    @Schema(description = "业务系统标识（DRONE/SHOP），用于多租户隔离", example = "DRONE")
    private String appId;

    @Schema(description = "用户/商户编号", example = "U202606151234567890")
    private String customerNo;

    @Schema(description = "支付分平台", example = "WECHAT_PAYSCORE",
            allowableValues = {"WECHAT_PAYSCORE", "ALIPAY_ZHIMA"})
    private String platform;

    @Schema(description = "支付分服务ID", example = "500001")
    private String serviceId;

    @Schema(description = "原始保证金金额（分）", example = "100000")
    private Long depositAmount;

    @Schema(description = "实际冻结金额（分）", example = "100000")
    private Long frozenAmount;

    @Schema(description = "完结实际扣款金额（分，0表示全额解冻）", example = "0")
    private Long actualAmount;

    @Schema(description = "服务订单状态", example = "PENDING_AUTH")
    private String status;

    @Schema(description = "授权时间")
    private LocalDateTime authTime;

    @Schema(description = "完结时间")
    private LocalDateTime completeTime;

    @Schema(description = "过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "第三方服务订单号")
    private String thirdPartyOrderNo;

    @Schema(description = "回调数据（JSON格式）")
    private String callbackData;

    @Schema(description = "扩展字段（JSON格式）")
    private String ext;

    @Schema(description = "备注信息")
    private String remark;

    @Schema(description = "回调通知地址")
    private String notifyUrl;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ==================== 领域方法 ====================

    /**
     * 获取状态枚举
     *
     * @return 状态枚举
     */
    public ServiceOrderStatus getStatusEnum() {
        return ServiceOrderStatus.getByCode(status);
    }

    /**
     * 设置状态枚举
     *
     * @param statusEnum 状态枚举
     */
    public void setStatusEnum(ServiceOrderStatus statusEnum) {
        if (statusEnum != null) {
            this.status = statusEnum.getCode();
        }
    }

    /**
     * 获取保证金金额（元）
     *
     * @return 保证金金额（元）
     */
    public BigDecimal getDepositAmountInYuan() {
        if (depositAmount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(depositAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * 获取冻结金额（元）
     *
     * @return 冻结金额（元）
     */
    public BigDecimal getFrozenAmountInYuan() {
        if (frozenAmount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(frozenAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * 获取实际扣款金额（元）
     *
     * @return 实际扣款金额（元）
     */
    public BigDecimal getActualAmountInYuan() {
        if (actualAmount == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(actualAmount).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * 检查是否待授权
     *
     * @return true表示待授权
     */
    public boolean isPendingAuth() {
        return ServiceOrderStatus.PENDING_AUTH.getCode().equals(status);
    }

    /**
     * 检查是否已授权
     *
     * @return true表示已授权
     */
    public boolean isAuthorized() {
        return ServiceOrderStatus.AUTHORIZED.getCode().equals(status);
    }

    /**
     * 检查是否服务中
     *
     * @return true表示服务中
     */
    public boolean isServiceActive() {
        return ServiceOrderStatus.SERVICE_ACTIVE.getCode().equals(status);
    }

    /**
     * 检查是否已完结
     *
     * @return true表示已完结
     */
    public boolean isCompleted() {
        return ServiceOrderStatus.COMPLETED.getCode().equals(status);
    }

    /**
     * 检查是否已取消
     *
     * @return true表示已取消
     */
    public boolean isCancelled() {
        return ServiceOrderStatus.CANCELLED.getCode().equals(status);
    }

    /**
     * 检查是否已过期
     *
     * @return true表示已过期
     */
    public boolean isExpired() {
        return ServiceOrderStatus.EXPIRED.getCode().equals(status)
                || (expireTime != null && LocalDateTime.now().isAfter(expireTime));
    }

    /**
     * 检查是否为终态
     *
     * @return true表示终态
     */
    public boolean isTerminal() {
        ServiceOrderStatus statusEnum = getStatusEnum();
        return statusEnum != null && statusEnum.isTerminal();
    }

    /**
     * 标记为已授权
     *
     * @param frozenAmount 冻结金额
     * @param thirdPartyOrderNo 第三方订单号
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsAuthorized(Long frozenAmount, String thirdPartyOrderNo) {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.AUTHORIZED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已授权");
        }
        this.status = target.getCode();
        this.frozenAmount = frozenAmount;
        this.thirdPartyOrderNo = thirdPartyOrderNo;
        this.authTime = LocalDateTime.now();
    }

    /**
     * 标记为服务中
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsServiceActive() {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.SERVICE_ACTIVE;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为服务中");
        }
        this.status = target.getCode();
    }

    /**
     * 标记为完结中
     *
     * @param actualAmount 实际扣款金额
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsCompleting(Long actualAmount) {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.COMPLETING;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为完结中");
        }
        // 完结金额校验：实际扣款不能超过冻结金额
        if (actualAmount != null && frozenAmount != null && actualAmount > frozenAmount) {
            throw new IllegalArgumentException("实际扣款金额不能超过冻结金额");
        }
        this.status = target.getCode();
        this.actualAmount = actualAmount != null ? actualAmount : 0L;
    }

    /**
     * 标记为已完结
     *
     * @param callbackData 回调数据
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsCompleted(String callbackData) {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.COMPLETED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已完结");
        }
        this.status = target.getCode();
        this.callbackData = callbackData;
        this.completeTime = LocalDateTime.now();
    }

    /**
     * 标记为已取消
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsCancelled() {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.CANCELLED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已取消");
        }
        this.status = target.getCode();
    }

    /**
     * 标记为已过期
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsExpired() {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.EXPIRED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为已过期");
        }
        this.status = target.getCode();
    }

    /**
     * 标记为失败
     *
     * @throws IllegalStateException 如果当前状态不允许此转换
     */
    public void markAsFailed() {
        ServiceOrderStatus current = getStatusEnum();
        ServiceOrderStatus target = ServiceOrderStatus.FAILED;
        if (current == null || !current.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "订单状态不允许从 " + (current != null ? current.getDescription() : "未知") + " 转换为失败");
        }
        this.status = target.getCode();
    }

    /**
     * 获取平台显示名称
     *
     * @return 平台显示名称
     */
    public String getPlatformDisplayName() {
        if (platform == null) {
            return "未知";
        }
        return switch (platform) {
            case "WECHAT_PAYSCORE" -> "微信支付分";
            case "ALIPAY_ZHIMA" -> "支付宝芝麻信用";
            default -> platform;
        };
    }

    /**
     * 获取状态显示名称
     *
     * @return 状态显示名称
     */
    public String getStatusDisplayName() {
        ServiceOrderStatus statusEnum = getStatusEnum();
        return statusEnum != null ? statusEnum.getDescription() : "未知";
    }
}
