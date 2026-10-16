-- Observations start when deployed; historical plan changes cannot be inferred from mutable subscriptions.
CREATE TABLE app.admin_customer_metrics (
    snapshot_hour TIMESTAMP WITH TIME ZONE PRIMARY KEY,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    total_customers BIGINT NOT NULL,
    free_users BIGINT NOT NULL,
    plan_99k_users BIGINT NOT NULL,
    plan_399k_users BIGINT NOT NULL,
    CONSTRAINT ck_admin_customer_metrics_counts CHECK (
        total_customers>=0 AND free_users>=0 AND plan_99k_users>=0 AND plan_399k_users>=0
        AND free_users+plan_99k_users+plan_399k_users=total_customers),
    CONSTRAINT ck_admin_customer_metrics_observation CHECK (
        captured_at>=snapshot_hour AND captured_at<snapshot_hour+INTERVAL '1 hour')
);
CREATE INDEX ix_admin_customer_metrics_captured ON app.admin_customer_metrics(captured_at);
