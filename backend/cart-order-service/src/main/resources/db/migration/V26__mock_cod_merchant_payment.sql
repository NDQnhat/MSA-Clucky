ALTER TABLE order_service.orders ADD COLUMN merchant_payment_status VARCHAR(24);

CREATE INDEX mock_delivery_receipts_order_attempt_phase_idx
    ON order_service.mock_delivery_receipts (order_id, attempt_number, phase);

-- A completed COD simulation could only reach this transition after the rider advance.
UPDATE order_service.orders o
SET merchant_payment_status = 'PAID_SIMULATED'
WHERE o.type = 'DELIVERY' AND o.payment_method = 'COD' AND o.payment_status = 'PENDING'
  AND o.status = 'COMPLETED' AND o.total_amount > 0
  AND EXISTS (SELECT 1 FROM order_service.mock_delivery_receipts r
              WHERE r.order_id = o.id AND r.phase = 'DELIVERED')
  AND EXISTS (SELECT 1 FROM order_service.order_status_history h
              WHERE h.order_id = o.id AND h.to_status = 'COMPLETED'
                AND h.changed_role = 'SHIPPING_SIMULATOR');
