-- =====================================================================
-- V1.1.4 门户单点闭环:应用自发起标记
-- ---------------------------------------------------------------------
-- 背景:
--   门户的 launch 接口生成 IAM 授权地址时不含 code_challenge,而公共客户端
--   (无密钥,按规范必须用 PKCE)在 Spring Authorization Server 里会被直接拒绝:
--   客户端 requireProofKey=true 且授权请求缺少 code_challenge 时,
--   授权端点返回 invalid_request。于是"点卡片即静默进入"对公共客户端必然失败。
--   门户是应用无关的中枢,拿不到应用侧的 code_verifier,也无法代为生成——
--   能持有 verifier 的只有应用自己。
--   故新增 sso_self_initiated 标记:应用自带 SSO 发起端点时,门户只负责把用户
--   送到应用入口,由应用生成 state/nonce/challenge 后再跳 IAM 授权端点,
--   PKCE 全程不受影响;门户不代发授权地址,也就不会产出半截的授权码流程。
-- 本脚本为向后兼容的追加式变更。
-- =====================================================================

ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS sso_self_initiated BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN iam_application_service.sso_self_initiated IS
    '应用是否自带 SSO 发起端点:TRUE 时门户只跳应用入口,由应用自行发起授权码流程(可完整使用 PKCE)';
