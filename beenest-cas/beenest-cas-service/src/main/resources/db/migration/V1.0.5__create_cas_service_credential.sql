-- 系统证书表
CREATE TABLE IF NOT EXISTS cas_service_credential (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL,
    secret_hash VARCHAR(255) NOT NULL,
    secret_version INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITHOUT TIME ZONE,
    revoked_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_cas_service_credential_service_id_version
    ON cas_service_credential (service_id, secret_version);
