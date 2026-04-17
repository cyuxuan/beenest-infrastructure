package club.beenest.payment.object.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(name = "SystemConfig", description = "系统配置信息")
public class SystemConfigDTO {
    @Schema(description = "客服电话")
    private String customerServicePhone;
    
    @Schema(description = "客服工作时间描述")
    private String workingHours;
    
    @Schema(description = "微信客服ID (企业微信客服)")
    private String wechatCorpId;

    @Schema(description = "是否开启定金支付模式")
    private Boolean isDepositMode;

    @Schema(description = "定金金额 (元)")
    private Double depositAmount;
}
