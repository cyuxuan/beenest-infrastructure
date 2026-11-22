-- ============================================================
-- V1.3.2 通知投递记录查询权限
-- 本迁移只补权限码,不建表:投递记录已在 V1.2.3 随账号自助服务建好
-- (iam_notification_log),此前只有写入路径,没有查询入口,
-- "用户说没收到"时只能连库排查。
-- 语义:notification:view 允许查看本域的通知投递记录(收件地址、模板、
--   投递结果与失败原因);记录只追加,不提供修改与删除入口。
-- 按域逐个补种(与 V1.1.5 同一口径):多租户部署下每个域的管理员
--   都需要看到自己域内的投递失败,只种默认域会让其它域永远查不到。
-- ============================================================

INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('notification:view', '查看通知投递记录', 'notification', 'view',
     '查看本域邮件/短信投递记录与失败原因')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission (realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.permission_code = 'notification:view'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);

COMMENT ON COLUMN iam_permission.permission_code IS '权限码;notification:view 用于查看通知投递记录';
