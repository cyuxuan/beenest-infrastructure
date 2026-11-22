-- ============================================================
-- beenest-iam 管理运维能力迁移(V1.0.3)
-- 1. iam_login_event  登录/认证事件(等保与风控审计)
-- 2. 补种运维权限码(用户锁定/下线、令牌吊销、同意管理、
--    登录事件、仪表盘、域密钥轮换)
-- 幂等:DDL 使用 IF NOT EXISTS,数据使用 NOT EXISTS 守卫,可重复执行。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 登录事件:认证链路的成功/失败/注销全记录
--    user_id 允许为空:账号不存在时的失败尝试同样要留痕(防撞库)
--    带 deleted 遵循全库软删除约定:清理历史事件用逻辑删除,不物理 DELETE
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS iam_login_event (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    user_id BIGINT,
    username VARCHAR(100) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    login_type VARCHAR(20),
    ip VARCHAR(64),
    user_agent VARCHAR(500),
    fail_reason VARCHAR(200),
    occurred_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_login_event IS '登录/认证事件(仅追加);event_type: LOGIN_SUCCESS/LOGIN_FAILURE/MFA_SUCCESS/MFA_FAILURE/LOGOUT';
COMMENT ON COLUMN iam_login_event.user_id IS '用户主键;账号不存在时为空,凭 username 回溯';
COMMENT ON COLUMN iam_login_event.login_type IS '登录方式: PASSWORD/WECHAT/OIDC 等';
CREATE INDEX IF NOT EXISTS idx_iam_login_event_realm_time ON iam_login_event(realm_id, occurred_time DESC);
CREATE INDEX IF NOT EXISTS idx_iam_login_event_user ON iam_login_event(realm_id, user_id, occurred_time DESC);
CREATE INDEX IF NOT EXISTS idx_iam_login_event_ip ON iam_login_event(realm_id, ip, occurred_time DESC);
CREATE INDEX IF NOT EXISTS idx_iam_login_event_username ON iam_login_event(realm_id, username, occurred_time DESC);

-- ------------------------------------------------------------
-- 2. 补种运维权限码(幂等)
-- ------------------------------------------------------------
INSERT INTO iam_permission(realm_id, permission_code, permission_name, resource, action)
SELECT r.id, v.code, v.name, v.resource, v.action
FROM iam_realm r,
     (VALUES
        ('user:lock', '锁定/解锁用户', 'user', 'lock'),
        ('user:logout', '强制用户全端下线', 'user', 'logout'),
        ('user:batch-status', '批量启用禁用用户', 'user', 'batch-status'),
        ('token:view', '查看已签发令牌', 'token', 'view'),
        ('token:revoke', '吊销令牌', 'token', 'revoke'),
        ('consent:view', '查看授权同意', 'consent', 'view'),
        ('consent:revoke', '撤销授权同意', 'consent', 'revoke'),
        ('login-event:view', '查看登录事件', 'login-event', 'view'),
        ('dashboard:view', '查看运营仪表盘', 'dashboard', 'view'),
        ('realm-key:view', '查看域签名密钥', 'realm-key', 'view'),
        ('realm-key:rotate', '轮换域签名密钥', 'realm-key', 'rotate')
     ) AS v(code, name, resource, action)
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_permission p WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

-- 3. SUPER_ADMIN 自动获得补种权限(幂等)
INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE r.realm_key = 'default'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
