CREATE TABLE order_service.web_order_operations (
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    order_id BIGINT UNIQUE REFERENCES order_service.orders(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
);
