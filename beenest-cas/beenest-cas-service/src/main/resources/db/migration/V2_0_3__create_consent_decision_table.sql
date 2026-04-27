-- Phase 6: 创建 CAS Consent 决策表
-- 该表用于属性授权记录，配合 Consent Webflow 和 /actuator/attributeConsent 使用。

CREATE SEQUENCE IF NOT EXISTS hibernate_sequence START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS consent_decision_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS consent_decision (
    id                  BIGSERIAL PRIMARY KEY,
    principal           VARCHAR(255) NOT NULL,
    service             VARCHAR(255) NOT NULL,
    created_date        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    options             INTEGER      NOT NULL DEFAULT 0,
    reminder            BIGINT       NOT NULL DEFAULT 14,
    reminder_time_unit   INTEGER      NOT NULL DEFAULT 7,
    tenant              VARCHAR(255),
    attributes          TEXT,
    CONSTRAINT uq_consent_decision_principal_service UNIQUE (principal, service)
);

CREATE INDEX IF NOT EXISTS idx_consent_decision_principal ON consent_decision(principal);
CREATE INDEX IF NOT EXISTS idx_consent_decision_service ON consent_decision(service);
