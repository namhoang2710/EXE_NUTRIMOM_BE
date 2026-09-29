CREATE TABLE app.audit_logs (
    id VARCHAR(36) NOT NULL,
    actor_user_id VARCHAR(36) NULL,
    action VARCHAR(60) NOT NULL,
    result VARCHAR(20) NULL,
    target_type VARCHAR(60) NULL,
    target_id VARCHAR(100) NULL,
    request_id VARCHAR(100) NULL,
    ip VARCHAR(45) NULL,
    metadata TEXT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_user_id)
        REFERENCES app.users(id)
);

CREATE INDEX ix_audit_logs_actor_created
    ON app.audit_logs(actor_user_id, created_at DESC);

CREATE INDEX ix_audit_logs_action_created
    ON app.audit_logs(action, created_at DESC);

CREATE INDEX ix_audit_logs_target
    ON app.audit_logs(target_type, target_id);

