package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * SAML 服务方登记请求(新增与修改共用)。
 *
 * @param spKey                     站内别名,域内唯一
 * @param entityId                  SP 实体 ID,断言的受众
 * @param displayName               展示名
 * @param acsUrl                    断言消费地址
 * @param sloUrl                    单点登出地址;为空表示不支持 SLO
 * @param nameIdFormat              NameID 格式;为空按 unspecified 处理
 * @param signAssertion             是否对断言签名
 * @param requireSignedAuthnRequest 是否强制要求 AuthnRequest 带签名
 * @param spCertificate             SP 签名证书(PEM);强制验签时必填
 * @param attributeMapping          属性映射:断言属性名 → 取值表达式
 * @param enabled                   是否启用
 */
public record SamlServiceProviderRequest(
        @NotBlank(message = "别名不能为空")
        @Pattern(regexp = "[A-Za-z0-9._-]{2,64}", message = "别名只能包含字母、数字、点、下划线与连字符,长度 2-64")
        String spKey,
        @NotBlank(message = "SP 实体 ID 不能为空")
        @Size(max = 512, message = "SP 实体 ID 过长")
        String entityId,
        @Size(max = 128, message = "展示名过长")
        String displayName,
        @NotBlank(message = "断言消费地址不能为空")
        @Size(max = 512, message = "断言消费地址过长")
        String acsUrl,
        @Size(max = 512, message = "登出地址过长")
        String sloUrl,
        @Size(max = 128, message = "NameID 格式过长")
        String nameIdFormat,
        @NotNull(message = "断言签名开关不能为空")
        Boolean signAssertion,
        @NotNull(message = "请求验签开关不能为空")
        Boolean requireSignedAuthnRequest,
        String spCertificate,
        Map<String, String> attributeMapping,
        @NotNull(message = "启用开关不能为空")
        Boolean enabled
) {
}
