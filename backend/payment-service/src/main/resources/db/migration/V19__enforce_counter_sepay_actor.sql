-- V18 is already applied in deployed environments. Tighten the POS actor constraint
-- without modifying that migration: PostgreSQL CHECK allows UNKNOWN (NULL).
ALTER TABLE payment_service.payments
    DROP CONSTRAINT counter_sepay_payment_shape;

ALTER TABLE payment_service.payments
    ADD CONSTRAINT counter_sepay_payment_shape CHECK (
        NOT pos_order OR (
            live_payment IS TRUE
            AND method = 'BANK_TRANSFER'
            AND customer_id IS NULL
            AND amount > 0
            AND pos_actor_id IS NOT NULL AND pos_actor_id > 0
            AND pos_request_key IS NOT NULL
        ) IS TRUE
    );
