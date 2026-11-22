-- Token 收敛上线:新应用默认按应用裁剪;存量应用保持 LEGACY_GLOBAL,由管理员逐应用切换。

ALTER TABLE iam_application_service
    ALTER COLUMN token_profile SET DEFAULT 'SCOPED';

COMMENT ON COLUMN iam_application_service.token_profile IS
    'SCOPED 按应用裁剪(新应用默认) / LEGACY_GLOBAL 迁移期兼容';
