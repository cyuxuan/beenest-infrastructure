-- ============================================================
-- 本迁移:Passkey / WebAuthn 凭证
--   新建 iam_webauthn_credential:每个用户可绑定多把凭证(手机、笔记本、安全密钥)
-- 语义要点:
--   - 一把凭证一行,不复用 iam_user_credential:
--     后者按 credential_type 一用户一类只保留一条(TOTP/恢复码),
--     而 Passkey 天然是多把并存,塞进去就得先破坏那张表的主键语义
--   - credential_id 全局唯一:同一把密钥不允许绑到两个账号上,
--     否则一次断言可以被两个身份复用
--   - sign_count 用于识别克隆:计数器只增不减,回退说明同一密钥
--     在两台设备上同时存在,必须按失窃处理
--   - credential_data 存 attested credential data 的 CBOR 原文
--     (AAGUID + credentialId + COSE 公钥),验签时由库还原,
--     拆成三个列反而要在读回时重新拼装,多一处出错机会
-- ============================================================

CREATE TABLE IF NOT EXISTS iam_webauthn_credential (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    credential_id VARCHAR(255) NOT NULL,
    credential_data TEXT NOT NULL,
    user_handle VARCHAR(64) NOT NULL,
    aaguid VARCHAR(64),
    sign_count BIGINT NOT NULL DEFAULT 0,
    transports VARCHAR(120),
    display_name VARCHAR(120),
    backup_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    backup_state BOOLEAN NOT NULL DEFAULT FALSE,
    last_used_time TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE iam_webauthn_credential IS 'Passkey(WebAuthn)凭证:一个用户可绑定多把';
COMMENT ON COLUMN iam_webauthn_credential.credential_id IS '凭证标识(rawId 的 Base64URL),全局唯一';
COMMENT ON COLUMN iam_webauthn_credential.credential_data IS 'attested credential data 的 CBOR 原文 Base64URL(AAGUID + 凭证标识 + COSE 公钥)';
COMMENT ON COLUMN iam_webauthn_credential.user_handle IS '无用户名登录用的用户句柄(Base64URL,同一用户的所有凭证共用),不对外暴露业务主键';
COMMENT ON COLUMN iam_webauthn_credential.aaguid IS '认证器型号标识(十六进制);仅用于展示,不参与校验';
COMMENT ON COLUMN iam_webauthn_credential.sign_count IS '签名计数器:只增不减,回退视为密钥被克隆';
COMMENT ON COLUMN iam_webauthn_credential.transports IS '认证器传输方式,逗号分隔(usb/nfc/ble/hybrid/internal)';
COMMENT ON COLUMN iam_webauthn_credential.display_name IS '用户自定义名称,便于在列表里区分设备';
COMMENT ON COLUMN iam_webauthn_credential.backup_eligible IS '是否可备份(同步型 Passkey)';
COMMENT ON COLUMN iam_webauthn_credential.backup_state IS '当前是否已备份';
COMMENT ON COLUMN iam_webauthn_credential.last_used_time IS '最近一次断言成功时间';

-- 全局唯一:一条凭证只能属于一个账号。软删的行不占位,
-- 用户删掉一把密钥后重新绑定同一把不会撞唯一键
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_webauthn_credential_id
    ON iam_webauthn_credential(credential_id) WHERE deleted = FALSE;

-- 账户中心的凭证列表按用户列举
CREATE INDEX IF NOT EXISTS idx_iam_webauthn_credential_user
    ON iam_webauthn_credential(realm_id, user_id) WHERE deleted = FALSE;

-- 无用户名登录:浏览器只回传 userHandle,按句柄反查凭证归属
CREATE INDEX IF NOT EXISTS idx_iam_webauthn_credential_handle
    ON iam_webauthn_credential(realm_id, user_handle) WHERE deleted = FALSE;
