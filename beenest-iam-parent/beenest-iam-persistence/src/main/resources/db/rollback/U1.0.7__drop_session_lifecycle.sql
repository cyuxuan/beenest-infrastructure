-- ============================================================
-- 回滚 V1.0.7(会话生命周期闭环)
-- 逆序执行:先删广播表与权限码 → 还原客户端会话索引 → 删客户端列。
-- 注意:删除 iam_client_session 唯一索引后,重复行不会被还原;
--       回滚后如再次升级,迁移脚本的去重步骤会重新收敛。
-- ============================================================

DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('session:broadcast');

DELETE FROM iam_permission
WHERE permission_code IN ('session:broadcast');

DROP TABLE IF EXISTS iam_logout_broadcast;

DROP INDEX IF EXISTS idx_iam_client_session_client;
DROP INDEX IF EXISTS uk_iam_client_session;

ALTER TABLE iam_client DROP COLUMN IF EXISTS reuse_refresh_tokens;
ALTER TABLE iam_client DROP COLUMN IF EXISTS backchannel_logout_uri;
ALTER TABLE iam_client DROP COLUMN IF EXISTS post_logout_redirect_uris;
