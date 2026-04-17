CREATE TABLE IF NOT EXISTS cas_service_credential (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL UNIQUE,
    secret_hash VARCHAR(128) NOT NULL,
    secret_salt VARCHAR(64) NOT NULL,
    secret_version BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cas_service_credential_service_id
    ON cas_service_credential (service_id);
