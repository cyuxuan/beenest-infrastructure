-- ============================================================
-- beenest-iam 客户端授权同意迁移(V1.0.4)
-- 1. iam_client.require_consent  是否要求用户授权同意(OAuth2 consent)
-- 存量库升级:补列带默认值,既有客户端行为不变(不要求同意)。
-- ============================================================
ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS require_consent BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN iam_client.require_consent IS '是否要求用户授权同意;开启后首次授权展示同意页,同意记录可管理端查询/撤销';
