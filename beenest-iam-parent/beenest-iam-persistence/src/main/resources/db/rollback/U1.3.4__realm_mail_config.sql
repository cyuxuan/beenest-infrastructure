-- 回滚 V1.3.4:移除域级发信配置、模板覆盖与对应权限码。
-- 先删授予再删权限码,避免残留指向已删权限的授予行。

DELETE FROM iam_role_permission rp
USING iam_permission p
WHERE rp.permission_id = p.id
  AND p.permission_code IN ('mail-config:view', 'mail-config:update');

DELETE FROM iam_permission WHERE permission_code IN ('mail-config:view', 'mail-config:update');

DROP TABLE IF EXISTS iam_mail_template;
DROP TABLE IF EXISTS iam_realm_mail_config;
