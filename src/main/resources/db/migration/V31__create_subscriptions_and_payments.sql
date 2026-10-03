-- Bảng gói đăng ký hội viên của người dùng
CREATE TABLE IF NOT EXISTS app.user_subscriptions (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    plan_tier VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date TIMESTAMP WITH TIME ZONE NOT NULL,
    end_date TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_user_subscriptions_user FOREIGN KEY (user_id)
        REFERENCES app.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_user_subscriptions_tier CHECK (plan_tier IN ('FREE', 'PLAN_99K', 'PLAN_399K')),
    CONSTRAINT ck_user_subscriptions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS ix_user_subscriptions_user_status
    ON app.user_subscriptions(user_id, status);

-- Bảng đơn hàng giao dịch thanh toán PayOS
CREATE TABLE IF NOT EXISTS app.payment_orders (
    id VARCHAR(36) NOT NULL,
    order_code BIGINT NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    plan_tier VARCHAR(20) NOT NULL,
    amount INT NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'VND',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payos_payment_link_id VARCHAR(100) NULL,
    checkout_url TEXT NULL,
    qr_code TEXT NULL,
    description VARCHAR(255) NULL,
    paid_at TIMESTAMP WITH TIME ZONE NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_orders PRIMARY KEY (id),
    CONSTRAINT ux_payment_orders_code UNIQUE (order_code),
    CONSTRAINT fk_payment_orders_user FOREIGN KEY (user_id)
        REFERENCES app.users(id),
    CONSTRAINT ck_payment_orders_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS ix_payment_orders_user_created
    ON app.payment_orders(user_id, created_at DESC);
