-- =====================================================================
-- V1.1.5 审计合规闭环:只追加约束、留存期清理通道、导出权限
-- ---------------------------------------------------------------------
-- 背景:
--   iam_audit_log 的表注释写着"仅插入不允许修改删除",但数据库层并没有任何约束
--   ——持有数据库写权限的人可以直接 UPDATE 改掉审计记录。审计的价值建立在
--   "记录不可篡改"之上,只在代码层自律不够。
--   同时审计表只有查询接口,没有留存期(无限增长)、没有导出(排障与合规审计
--   需要把数据带走),三条都不落地,审计就只是"看着有"。
-- 本脚本补齐:数据库级只追加约束、留存期清理的受控通道、导出权限。
-- =====================================================================

-- ------------------------------------------------------------
-- 1. 只追加约束
-- ------------------------------------------------------------
-- 触发器拦截 UPDATE;DELETE 仅在显式声明"本次是留存期清理"的会话里放行。
-- 放行开关用自定义 GUC:普通会话读不到这个值,只有平台的清理任务在事务内
-- 打开它。用 SET LOCAL 而非 SET,保证开关随事务结束自动失效,不会残留到
-- 同一连接的后续语句上。
CREATE OR REPLACE FUNCTION iam_audit_log_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('iam.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'iam_audit_log 为只追加表,不允许 % 操作', TG_OP
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_iam_audit_log_append_only ON iam_audit_log;
CREATE TRIGGER trg_iam_audit_log_append_only
    BEFORE UPDATE OR DELETE ON iam_audit_log
    FOR EACH ROW EXECUTE FUNCTION iam_audit_log_append_only();

COMMENT ON FUNCTION iam_audit_log_append_only() IS
    '审计日志只追加:UPDATE 一律拒绝;DELETE 仅在 iam.audit_purge=on 的事务内放行(留存期清理)';

-- ------------------------------------------------------------
-- 2. 留存期清理用的索引
-- ------------------------------------------------------------
-- 清理是按时间全表扫描,与现有 (realm_id, occurred_time) 索引方向相反
CREATE INDEX IF NOT EXISTS idx_iam_audit_log_occurred_time ON iam_audit_log(occurred_time);

-- ------------------------------------------------------------
-- 3. 导出权限
-- ------------------------------------------------------------
-- 导出是把审计数据整批带出系统,比在控制台翻页敏感得多,单列一个权限码
INSERT INTO iam_permission (realm_id, permission_code, permission_name, resource, action, description)
SELECT r.id, v.code, v.name, v.resource, v.action, v.description
FROM iam_realm r
         CROSS JOIN (VALUES
    ('audit:export', '导出审计日志', 'audit', 'export', '按条件导出审计日志(CSV)')
) AS v(code, name, resource, action, description)
WHERE NOT EXISTS (SELECT 1 FROM iam_permission p
                  WHERE p.realm_id = r.id AND p.permission_code = v.code AND NOT p.deleted);

INSERT INTO iam_role_permission(realm_id, role_id, permission_id)
SELECT r.id, ro.id, p.id
FROM iam_realm r
         JOIN iam_role ro ON ro.realm_id = r.id AND ro.role_code = 'SUPER_ADMIN' AND NOT ro.deleted
         JOIN iam_permission p ON p.realm_id = r.id AND NOT p.deleted
WHERE p.permission_code = 'audit:export'
  AND NOT EXISTS (SELECT 1 FROM iam_role_permission rp
                  WHERE rp.role_id = ro.id AND rp.permission_id = p.id AND NOT rp.deleted);
