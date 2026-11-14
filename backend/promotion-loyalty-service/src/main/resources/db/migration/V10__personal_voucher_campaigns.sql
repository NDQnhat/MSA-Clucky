-- Expand existing shared-code vouchers without modifying historical orders or counters.
ALTER TABLE promotion_service.vouchers
  ALTER COLUMN code DROP NOT NULL,
  ADD COLUMN campaign_kind VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN applicability VARCHAR(16) NOT NULL DEFAULT 'ALL',
  ADD CONSTRAINT voucher_campaign_kind_valid CHECK (campaign_kind IN ('LEGACY', 'PERSONAL')),
  ADD CONSTRAINT voucher_applicability_valid CHECK (applicability IN ('ALL', 'SELECTED')),
  ADD CONSTRAINT voucher_campaign_code_valid CHECK (
    (campaign_kind = 'LEGACY' AND code IS NOT NULL)
    OR (campaign_kind = 'PERSONAL' AND code IS NULL));

CREATE TABLE promotion_service.voucher_campaign_stores (
  campaign_id BIGINT NOT NULL REFERENCES promotion_service.vouchers(id) ON DELETE CASCADE,
  store_id BIGINT NOT NULL CHECK (store_id > 0),
  PRIMARY KEY (campaign_id, store_id)
);
CREATE INDEX voucher_campaign_stores_store ON promotion_service.voucher_campaign_stores(store_id);

CREATE TABLE promotion_service.customer_voucher_instances (
  id UUID PRIMARY KEY,
  campaign_id BIGINT NOT NULL REFERENCES promotion_service.vouchers(id),
  customer_id BIGINT NOT NULL CHECK (customer_id > 0),
  state VARCHAR(16) NOT NULL DEFAULT 'ISSUED' CHECK (state IN ('ISSUED','RESERVED','REDEEMED','EXPIRED','REVOKED')),
  selector VARCHAR(16) NOT NULL UNIQUE,
  code_salt BYTEA NOT NULL,
  code_verifier BYTEA NOT NULL,
  code_ciphertext TEXT NOT NULL,
  code_key_version SMALLINT NOT NULL DEFAULT 1,
  reserved_order_id BIGINT,
  redeemed_order_id BIGINT,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (campaign_id, customer_id),
  CONSTRAINT customer_voucher_state_order CHECK (
    (state = 'RESERVED' AND reserved_order_id IS NOT NULL)
    OR (state <> 'RESERVED' AND reserved_order_id IS NULL)),
  CONSTRAINT customer_voucher_redeemed_order CHECK (
    (state = 'REDEEMED' AND redeemed_order_id IS NOT NULL)
    OR (state <> 'REDEEMED' AND redeemed_order_id IS NULL))
);
CREATE UNIQUE INDEX customer_voucher_active_reservation
  ON promotion_service.customer_voucher_instances(reserved_order_id)
  WHERE reserved_order_id IS NOT NULL;
CREATE INDEX customer_voucher_owner ON promotion_service.customer_voucher_instances(customer_id, issued_at DESC);

ALTER TABLE promotion_service.voucher_reservations
  ADD COLUMN voucher_instance_id UUID REFERENCES promotion_service.customer_voucher_instances(id),
  ADD COLUMN instance_owner_id BIGINT CHECK (instance_owner_id > 0);
-- Replace only the historic shape check; preserve every unrelated constraint.
DO $$
DECLARE old_check RECORD;
BEGIN
  FOR old_check IN SELECT conname FROM pg_constraint
    WHERE conrelid = 'promotion_service.voucher_reservations'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%customer_id IS NOT NULL%'
      AND pg_get_constraintdef(oid) LIKE '%code IS NOT NULL%'
  LOOP
    EXECUTE format('ALTER TABLE promotion_service.voucher_reservations DROP CONSTRAINT %I', old_check.conname);
  END LOOP;
END $$;
ALTER TABLE promotion_service.voucher_reservations
  ADD CONSTRAINT voucher_reservation_reference_shape CHECK (
    state = 'RELEASED' OR (
      voucher_id IS NOT NULL AND store_id IS NOT NULL AND order_amount IS NOT NULL AND discount_amount IS NOT NULL
      AND ((voucher_instance_id IS NULL AND customer_id IS NOT NULL AND code IS NOT NULL)
        OR (voucher_instance_id IS NOT NULL AND instance_owner_id IS NOT NULL))));
CREATE UNIQUE INDEX voucher_reservation_active_instance
  ON promotion_service.voucher_reservations(voucher_instance_id)
  WHERE voucher_instance_id IS NOT NULL AND state = 'RESERVED';

ALTER TABLE promotion_service.voucher_usages
  ADD COLUMN voucher_instance_id UUID REFERENCES promotion_service.customer_voucher_instances(id),
  ADD COLUMN reversed_at TIMESTAMPTZ,
  ADD COLUMN reversal_id VARCHAR(160) UNIQUE;
CREATE UNIQUE INDEX voucher_usage_active_instance
  ON promotion_service.voucher_usages(voucher_instance_id)
  WHERE voucher_instance_id IS NOT NULL AND reversed_at IS NULL;

-- Counter orders can have no linked customer account; personal voucher ownership
-- is recorded separately in instance_owner_id and voucher_usages.customer_id.
ALTER TABLE promotion_service.voucher_order_events ALTER COLUMN customer_id DROP NOT NULL;

CREATE TABLE promotion_service.voucher_cancellation_audit (
  id BIGSERIAL PRIMARY KEY,
  order_id BIGINT NOT NULL,
  instance_id UUID NOT NULL REFERENCES promotion_service.customer_voucher_instances(id),
  action VARCHAR(24) NOT NULL CHECK (action IN ('RELEASE', 'REVERSE', 'REFUND_NO_RESTORE')),
  event_id VARCHAR(160) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
