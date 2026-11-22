-- ============================================================
-- beenest-iam 会话生命周期闭环迁移(V1.0.7)
-- 1. iam_client 增加登出能力字段:登出回跳白名单、后通道登出地址
-- 2. iam_client 增加刷新令牌轮换开关(默认沿用复用,显式开启轮换)
-- 3. iam_client_session 补唯一约束:同一会话内同一客户端只记一行
-- 4. 新增 iam_logout_broadcast:单点登出广播的投递记录(可观测、可重放)
-- 说明:此前用户登出 IAM 后,接入应用各自持有的会话仍然有效,
--       形成"主账号已退出、业务系统仍在线"的越权窗口。本次补齐
--       客户端登出配置、客户端会话落库与广播投递记录三张拼图。
-- ============================================================

-- 1. 登出相关客户端配置
ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS post_logout_redirect_uris TEXT;
COMMENT ON COLUMN iam_client.post_logout_redirect_uris IS
        '登出后允许回跳的地址列表(逗号分隔);为空表示只能回 IAM 登录页';

ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS backchannel_logout_uri VARCHAR(500);
COMMENT ON COLUMN iam_client.backchannel_logout_uri IS
        'OIDC 后通道登出接收地址;为空表示该应用不参与单点登出广播';

-- 2. 刷新令牌轮换:默认关闭以兼容存量客户端,开启后旧令牌立即失效
ALTER TABLE iam_client ADD COLUMN IF NOT EXISTS reuse_refresh_tokens BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN iam_client.reuse_refresh_tokens IS
        '刷新令牌是否复用:TRUE 复用同一枚(兼容);FALSE 每次刷新轮换,旧令牌立即失效';

-- 3. 客户端会话唯一性:同一用户会话内同一客户端只保留一行(先去重再加约束)
UPDATE iam_client_session cs
SET deleted = TRUE, updated_time = CURRENT_TIMESTAMP
WHERE cs.deleted = FALSE
  AND cs.id NOT IN (SELECT MIN(id)
                    FROM iam_client_session
                    WHERE deleted = FALSE
                    GROUP BY user_session_id, client_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_client_session
    ON iam_client_session(user_session_id, client_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_client_session_client
    ON iam_client_session(realm_id, client_id) WHERE deleted = FALSE;

-- 4. 登出广播投递记录
CREATE TABLE IF NOT EXISTS iam_logout_broadcast (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    sid VARCHAR(64) NOT NULL,
    user_id BIGINT,
    client_id BIGINT,
    client_key VARCHAR(100),
    target_uri VARCHAR(500),
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    http_status INT,
    error_message VARCHAR(500),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_logout_broadcast IS
        '单点登出广播记录;state:PENDING 待投递 / SENT 已投递 / FAILED 投递失败 / SKIPPED 未登记接收地址';
COMMENT ON COLUMN iam_logout_broadcast.sid IS '被结束的 IAM 会话标识,即后通道登出令牌中的 sid';
CREATE INDEX IF NOT EXISTS idx_iam_logout_broadcast_sid
    ON iam_logout_broadcast(realm_id, sid) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_logout_broadcast_created
    ON iam_logout_broadcast(realm_id, created_time DESC) WHERE deleted = FALSE;

-- 5. 会话与令牌管理的查看权限(广播记录查询复用 session:view)
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('session:broadcast', '查看登出广播', 'session', 'broadcast', '查看单点登出广播的投递记录')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.permission_code IN ('session:broadcast')
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
