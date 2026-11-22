-- 回滚 V1.1.9:撤销 LDAP/AD 用户联合。
-- 删除后域级目录配置(含 bind 口令密文)全部丢失,LDAP 兜底认证链路失效;
-- 此前由 JIT 创建的本地用户保留,仍可用本地口令登录(口令由 LDAP 首次认证时随机生成,
-- 实际等于无法登录),需由管理员重置口令或重新启用目录联合。

-- 权限码:先删授予再删权限码
DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('ldap-config:view', 'ldap-config:update');

DELETE FROM iam_permission
WHERE permission_code IN ('ldap-config:view', 'ldap-config:update');

-- 目录配置表(含唯一索引)
DROP TABLE IF EXISTS iam_realm_ldap_config;
