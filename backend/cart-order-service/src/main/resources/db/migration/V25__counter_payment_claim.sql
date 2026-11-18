-- A payment initiation permanently fences POS payment-method changes.
-- Stored with the Order so payment-service and cart-order-service need not share a DB lock.
ALTER TABLE order_service.orders
    ADD COLUMN counter_payment_started BOOLEAN NOT NULL DEFAULT FALSE;
