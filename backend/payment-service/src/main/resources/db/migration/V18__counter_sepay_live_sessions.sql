-- Staff POS transfers reuse the live SePay receipt reconciliation ledger.
-- COUNTER orders have no customer identity; keep the exception explicit and
-- never relax the bank/store/reference requirements of live_payment_snapshot.
ALTER TABLE payment_service.payments
    ADD COLUMN pos_order BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pos_actor_id BIGINT,
    ADD COLUMN pos_request_key UUID,
    ADD COLUMN counter_cash_amount NUMERIC(12,0) NOT NULL DEFAULT 0
        CHECK (counter_cash_amount >= 0);

ALTER TABLE payment_service.payments DROP CONSTRAINT live_payment_snapshot;
ALTER TABLE payment_service.payments
    ADD CONSTRAINT live_payment_snapshot CHECK (NOT live_payment OR (
        (customer_id IS NOT NULL AND NOT pos_order AND counter_cash_amount = 0
            OR customer_id IS NULL AND pos_order)
        AND store_id IS NOT NULL AND expires_at IS NOT NULL AND amount > 0
        AND bank_code IS NOT NULL AND account_no IS NOT NULL AND transfer_content IS NOT NULL
    ));

ALTER TABLE payment_service.payments
    ADD CONSTRAINT counter_sepay_payment_shape CHECK (
        NOT pos_order OR (live_payment AND method = 'BANK_TRANSFER'
            AND customer_id IS NULL AND amount > 0 AND pos_actor_id > 0 AND pos_request_key IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_pos_sepay_request_key
    ON payment_service.payments(pos_request_key) WHERE pos_request_key IS NOT NULL;
