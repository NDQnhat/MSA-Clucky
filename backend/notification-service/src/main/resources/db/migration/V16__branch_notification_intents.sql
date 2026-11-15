ALTER TABLE notification_service.notifications
    ADD COLUMN store_id BIGINT CHECK (store_id > 0);

CREATE INDEX idx_notifications_user_store_created
    ON notification_service.notifications(user_id,store_id,created_at DESC,id DESC);

CREATE TABLE notification_service.branch_notification_intents (
    id BIGSERIAL PRIMARY KEY,
    operation_key VARCHAR(180) NOT NULL UNIQUE,
    event_id VARCHAR(160) NOT NULL,
    store_id BIGINT NOT NULL CHECK (store_id > 0),
    role VARCHAR(30) NOT NULL CHECK (role IN ('STAFF','STORE_MANAGER')),
    title VARCHAR(180) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(30) NOT NULL CHECK (type IN ('ORDER','INVENTORY')),
    ref_id BIGINT NOT NULL CHECK (ref_id > 0),
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_branch_notification_pending
    ON notification_service.branch_notification_intents(next_attempt_at,id)
    WHERE delivered_at IS NULL;
