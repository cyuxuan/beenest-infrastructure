package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Passkey(WebAuthn)凭证实体(iam_webauthn_credential)。
 * <p>一个用户可绑定多把凭证,每把一行;平台只保存公钥与签名计数器,
 * 私钥留在认证器设备上。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class WebAuthnCredential extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 所属用户 */
    private Long userId;
    /** 凭证标识(rawId 的 Base64URL),全局唯一 */
    private String credentialId;
    /** attested credential data 的 CBOR 原文 Base64URL(AAGUID + 凭证标识 + COSE 公钥) */
    private String credentialData;
    /** 无用户名登录用的用户句柄(Base64URL,同一用户的所有凭证共用) */
    private String userHandle;
    /** 认证器型号标识(十六进制),仅用于展示 */
    private String aaguid;
    /** 签名计数器:只增不减,回退视为密钥被克隆 */
    private Long signCount;
    /** 传输方式,逗号分隔(usb/nfc/ble/hybrid/internal) */
    private String transports;
    /** 用户自定义名称 */
    private String displayName;
    /** 是否可备份(同步型 Passkey) */
    private Boolean backupEligible;
    /** 当前是否已备份 */
    private Boolean backupState;
    /** 最近一次断言成功时间 */
    private LocalDateTime lastUsedTime;
}
