-- Store counter orders may apply an existing shared-code (LEGACY) campaign
-- without a registered customer account. Personal instances retain their
-- independent verified instance_owner_id.
ALTER TABLE promotion_service.voucher_reservations
  DROP CONSTRAINT voucher_reservation_reference_shape;

ALTER TABLE promotion_service.voucher_reservations
  ADD CONSTRAINT voucher_reservation_reference_shape CHECK (
    state = 'RELEASED' OR (
      voucher_id IS NOT NULL AND store_id IS NOT NULL AND order_amount IS NOT NULL
      AND discount_amount IS NOT NULL
      AND ((voucher_instance_id IS NULL AND code IS NOT NULL)
        OR (voucher_instance_id IS NOT NULL AND instance_owner_id IS NOT NULL))
    )
  );

ALTER TABLE promotion_service.voucher_usages
  ALTER COLUMN customer_id DROP NOT NULL;
