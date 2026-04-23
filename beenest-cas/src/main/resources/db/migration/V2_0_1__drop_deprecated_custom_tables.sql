-- Phase 6: 删除已被 CAS 原生模块替代的自定义表
-- 这些表的功能已由 Inspektr Audit、Consent、AUP、Surrogate 等原生模块接管

-- 应用访问控制表 — 已由 BeenestAccessStrategy (no-op) + CAS 原生服务管理替代
DROP TABLE IF EXISTS cas_app_access;

-- 用户变更日志表 — 已由 Inspektr Audit (cas_audit_trail) 替代
DROP TABLE IF EXISTS cas_user_change_log;

-- 同步策略配置表 — 已由 CAS 原生用户同步机制替代
DROP TABLE IF EXISTS cas_sync_strategy;

-- 服务凭证管理表 — 已由 CAS 原生服务注册管理替代
DROP TABLE IF EXISTS cas_service_credential;

-- 自定义审计日志表 — 已由 Inspektr Audit (cas_audit_trail + cas_audit_trail_action) 替代
DROP TABLE IF EXISTS cas_auth_audit_log;
