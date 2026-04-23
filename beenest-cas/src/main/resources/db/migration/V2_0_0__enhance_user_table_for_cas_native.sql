-- ============================================================
-- V2.0.0: 增强 cas_user 表以支持 CAS 原生密码管理
-- ============================================================
-- Phase 3 变更:
--   1. 添加密码管理所需字段（密码变更时间、过期时间、强制改密标记）
--   2. 移除自定义 MFA secret 字段（由 CAS gauth-jpa 接管）
-- ============================================================

-- 密码管理增强字段
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS password_changed_time TIMESTAMP;
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS password_expiry_time TIMESTAMP;
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;

-- 移除自定义 MFA secret 字段（CAS gauth-jpa 使用独立的 device_registration_store 表）
ALTER TABLE cas_user DROP COLUMN IF EXISTS mfa_secret_encrypted;
