package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 应用服务实体(iam_application_service),作为应用门户/启动台的展示来源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ApplicationService extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 应用编码(域内唯一) */
    private String appCode;
    /** 访问令牌受众(aud),多个以英文逗号分隔;为空按 urn:iam:app:{appCode} 派生 */
    private String audience;
    /** 应用名称 */
    private String appName;
    /** 应用类型:WEB/APP/MINI_PROGRAM/API */
    private String appType;
    /** 单点接入方式:OIDC/CAS/SAML/BEARER */
    private String ssoMode;
    /** 关联客户端主键(iam_client.id) */
    private Long ssoClientPk;
    /** 图标地址 */
    private String iconUrl;
    /** 入口地址:门户启动时的跳转目标,由应用在入口页自行发起授权 */
    private String entryUrl;
    /** 分类 */
    private String category;
    /** 菜单托管模式:IAM 全量托管 / HYBRID 仅一级模块 / APP 应用自管 */
    private String menuMode;
    /** 授权模式:IAM_MANAGED IAM 托管 / MAPPED 角色映射 / APP_MANAGED 应用自管 */
    private String authorizationMode;
    /** 令牌收敛策略:SCOPED 按应用裁剪 / LEGACY_GLOBAL 迁移期兼容 */
    private String tokenProfile;
    /** 排序号 */
    private Integer sortOrder;
    /** 可见性:PUBLIC 全员 / ROLE 按角色授权 / PERMISSION 按权限 */
    private String visibility;
    /** 所需权限编码(visibility 为 PERMISSION 时) */
    private String requiredPermissionCode;
    /** 是否启用 */
    private Boolean enabled;
    /** 说明 */
    private String description;
}
