-- Printed paper voucher batches and per-sheet QR credentials (counter channel).
ALTER TABLE promotion_service.vouchers
  ADD COLUMN printable BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN max_print_ratio NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
  ADD COLUMN max_print_per_store INTEGER,
  ADD COLUMN printed_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN printed_unredeemed INTEGER NOT NULL DEFAULT 0,
  ADD CONSTRAINT voucher_print_counts_nonnegative CHECK (printed_count >= 0 AND printed_unredeemed >= 0),
  ADD CONSTRAINT voucher_max_print_ratio_valid CHECK (max_print_ratio > 0 AND max_print_ratio <= 1),
  ADD CONSTRAINT voucher_max_print_per_store_valid CHECK (max_print_per_store IS NULL OR max_print_per_store > 0),
  ADD CONSTRAINT voucher_printable_requires_limit CHECK (NOT printable OR (total_limit IS NOT NULL AND total_limit > 0)),
  ADD CONSTRAINT voucher_printed_ratio_cap CHECK (total_limit IS NULL OR total_limit <= 0 OR printed_count <= FLOOR(total_limit * max_print_ratio));

ALTER TABLE promotion_service.vouchers
  DROP CONSTRAINT IF EXISTS voucher_quota;

ALTER TABLE promotion_service.vouchers
  ADD CONSTRAINT voucher_quota CHECK (
    total_limit IS NULL
    OR total_limit <= 0
    OR used_count::BIGINT + reserved_count + printed_unredeemed <= total_limit
  );

CREATE TABLE promotion_service.voucher_print_batches (
  id BIGSERIAL PRIMARY KEY,
  batch_code VARCHAR(40) NOT NULL UNIQUE,
  campaign_id BIGINT NOT NULL REFERENCES promotion_service.vouchers(id),
  store_id BIGINT CHECK (store_id IS NULL OR store_id > 0),
  store_name VARCHAR(255),
  quantity INTEGER NOT NULL CHECK (quantity > 0 AND quantity <= 200),
  allowed_channels VARCHAR(32) NOT NULL DEFAULT 'COUNTER' CHECK (allowed_channels IN ('COUNTER')),
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PARTIALLY_REVOKED', 'REVOKED')),
  note VARCHAR(500),
  created_by BIGINT NOT NULL,
  created_by_role VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_by BIGINT,
  revoked_at TIMESTAMPTZ,
  revoke_reason VARCHAR(500)
);

CREATE INDEX voucher_print_batches_campaign_idx
  ON promotion_service.voucher_print_batches(campaign_id, created_at DESC);
CREATE INDEX voucher_print_batches_store_idx
  ON promotion_service.voucher_print_batches(store_id, created_at DESC);

CREATE TABLE promotion_service.printed_voucher_instances (
  id UUID PRIMARY KEY,
  batch_id BIGINT NOT NULL REFERENCES promotion_service.voucher_print_batches(id),
  campaign_id BIGINT NOT NULL REFERENCES promotion_service.vouchers(id),
  store_id BIGINT CHECK (store_id IS NULL OR store_id > 0),
  sequence_no INTEGER NOT NULL CHECK (sequence_no > 0),
  state VARCHAR(16) NOT NULL DEFAULT 'ISSUED' CHECK (state IN ('ISSUED', 'RESERVED', 'REDEEMED', 'EXPIRED', 'REVOKED')),
  selector VARCHAR(16) NOT NULL UNIQUE,
  code_salt BYTEA NOT NULL,
  code_verifier BYTEA NOT NULL,
  code_ciphertext TEXT NOT NULL,
  code_key_version SMALLINT NOT NULL DEFAULT 1,
  reserved_order_id BIGINT,
  redeemed_order_id BIGINT,
  issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE (batch_id, sequence_no),
  CONSTRAINT printed_voucher_state_order CHECK (
    (state = 'RESERVED' AND reserved_order_id IS NOT NULL)
    OR (state <> 'RESERVED' AND reserved_order_id IS NULL)),
  CONSTRAINT printed_voucher_redeemed_order CHECK (
    (state = 'REDEEMED' AND redeemed_order_id IS NOT NULL)
    OR (state <> 'REDEEMED' AND redeemed_order_id IS NULL))
);

CREATE UNIQUE INDEX printed_voucher_active_reservation
  ON promotion_service.printed_voucher_instances(reserved_order_id)
  WHERE reserved_order_id IS NOT NULL;
CREATE INDEX printed_voucher_batch_idx
  ON promotion_service.printed_voucher_instances(batch_id, sequence_no);
CREATE INDEX printed_voucher_campaign_state_idx
  ON promotion_service.printed_voucher_instances(campaign_id, state);

ALTER TABLE promotion_service.voucher_reservations
  ADD COLUMN printed_voucher_id UUID REFERENCES promotion_service.printed_voucher_instances(id);

ALTER TABLE promotion_service.voucher_reservations
  DROP CONSTRAINT IF EXISTS voucher_reservation_reference_shape;

ALTER TABLE promotion_service.voucher_reservations
  ADD CONSTRAINT voucher_reservation_reference_shape CHECK (
    state = 'RELEASED' OR (
      voucher_id IS NOT NULL AND store_id IS NOT NULL AND order_amount IS NOT NULL AND discount_amount IS NOT NULL
      AND (
        (voucher_instance_id IS NULL AND printed_voucher_id IS NULL AND code IS NOT NULL)
        OR (voucher_instance_id IS NOT NULL AND printed_voucher_id IS NULL AND instance_owner_id IS NOT NULL)
        OR (printed_voucher_id IS NOT NULL AND voucher_instance_id IS NULL)
      )
    )
  );

CREATE UNIQUE INDEX voucher_reservation_active_printed
  ON promotion_service.voucher_reservations(printed_voucher_id)
  WHERE printed_voucher_id IS NOT NULL AND state = 'RESERVED';

ALTER TABLE promotion_service.voucher_usages
  ADD COLUMN printed_voucher_id UUID REFERENCES promotion_service.printed_voucher_instances(id);

CREATE UNIQUE INDEX voucher_usage_active_printed
  ON promotion_service.voucher_usages(printed_voucher_id)
  WHERE printed_voucher_id IS NOT NULL AND reversed_at IS NULL;

ALTER TABLE promotion_service.voucher_cancellation_audit
  ALTER COLUMN instance_id DROP NOT NULL,
  ADD COLUMN printed_voucher_id UUID REFERENCES promotion_service.printed_voucher_instances(id),
  ADD CONSTRAINT voucher_cancellation_audit_target CHECK (
    (instance_id IS NOT NULL AND printed_voucher_id IS NULL)
    OR (instance_id IS NULL AND printed_voucher_id IS NOT NULL)
  );
