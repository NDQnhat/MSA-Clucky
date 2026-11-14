-- Expand existing customer wallets in place; retain IDs and original transaction history.
ALTER TABLE promotion_service.member_points
  ALTER COLUMN customer_id DROP NOT NULL,
  ALTER COLUMN total_points TYPE BIGINT,
  ALTER COLUMN reserved_points TYPE BIGINT,
  ADD COLUMN owner_type VARCHAR(12) NOT NULL DEFAULT 'CUSTOMER',
  ADD COLUMN phone_key CHAR(64),
  ADD COLUMN phone_key_version INTEGER,
  ADD COLUMN wallet_state VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN merged_into_wallet_id BIGINT REFERENCES promotion_service.member_points(id),
  ADD CONSTRAINT wallet_owner_check CHECK (
    (owner_type = 'CUSTOMER' AND customer_id IS NOT NULL AND phone_key IS NULL)
    OR (owner_type = 'PHONE' AND customer_id IS NULL AND phone_key IS NOT NULL AND phone_key_version IS NOT NULL)),
  ADD CONSTRAINT wallet_state_check CHECK (wallet_state IN ('ACTIVE','MERGED'));
CREATE UNIQUE INDEX wallet_phone_key_unique ON promotion_service.member_points(phone_key_version,phone_key)
  WHERE owner_type='PHONE';

ALTER TABLE promotion_service.point_transactions
  ALTER COLUMN customer_id DROP NOT NULL,
  ALTER COLUMN change TYPE BIGINT,
  ALTER COLUMN balance_after TYPE BIGINT,
  ADD COLUMN wallet_id BIGINT REFERENCES promotion_service.member_points(id),
  ADD COLUMN origin_wallet_id BIGINT,
  ADD COLUMN source_type VARCHAR(24),
  ADD COLUMN source_ref VARCHAR(160);
UPDATE promotion_service.point_transactions t SET wallet_id=w.id
  FROM promotion_service.member_points w WHERE t.customer_id=w.customer_id;
CREATE INDEX point_history_wallet ON promotion_service.point_transactions(wallet_id, created_at DESC,id DESC);

ALTER TABLE promotion_service.loyalty_orders
  ALTER COLUMN customer_id DROP NOT NULL,
  ALTER COLUMN points TYPE BIGINT,
  ALTER COLUMN earned_points TYPE BIGINT,
  ADD COLUMN wallet_id BIGINT REFERENCES promotion_service.member_points(id),
  ADD COLUMN original_wallet_id BIGINT,
  ADD COLUMN pending_earn BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN store_id BIGINT,
  ADD COLUMN staff_id BIGINT,
  ADD COLUMN authentication_method VARCHAR(16);
UPDATE promotion_service.loyalty_orders o SET wallet_id=w.id,original_wallet_id=w.id
  FROM promotion_service.member_points w WHERE o.customer_id=w.customer_id;
CREATE INDEX loyalty_order_wallet_idx ON promotion_service.loyalty_orders(wallet_id);

ALTER TABLE promotion_service.loyalty_policy
  ADD COLUMN max_earn_per_order BIGINT NOT NULL DEFAULT 0 CHECK(max_earn_per_order>=0),
  ADD COLUMN max_earn_per_day BIGINT NOT NULL DEFAULT 0 CHECK(max_earn_per_day>=0),
  ADD COLUMN max_point_balance BIGINT NOT NULL DEFAULT 0 CHECK(max_point_balance>=0);

CREATE TABLE promotion_service.loyalty_phone_claims (
  source_wallet_id BIGINT PRIMARY KEY REFERENCES promotion_service.member_points(id),
  target_wallet_id BIGINT NOT NULL REFERENCES promotion_service.member_points(id),
  verified_customer_id BIGINT NOT NULL,
  verification_event_id VARCHAR(160) NOT NULL UNIQUE,
  amount BIGINT NOT NULL CHECK(amount>=0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE promotion_service.loyalty_daily_earn_usage (
  wallet_id BIGINT NOT NULL REFERENCES promotion_service.member_points(id),
  local_date DATE NOT NULL,
  earned BIGINT NOT NULL DEFAULT 0 CHECK(earned>=0),
  held BIGINT NOT NULL DEFAULT 0 CHECK(held>=0),
  PRIMARY KEY(wallet_id,local_date)
);
CREATE TABLE promotion_service.loyalty_verified_phones (
  phone_key CHAR(64) PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  binding_version BIGINT NOT NULL,
  verification_event_id VARCHAR(160) NOT NULL UNIQUE,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX loyalty_verified_customer_idx ON promotion_service.loyalty_verified_phones(customer_id);

-- Proofs are short-lived, hashed and consumed under a SQL row lock with the wallet reserve.
CREATE TABLE promotion_service.loyalty_scan_proofs (
  id BIGSERIAL PRIMARY KEY,
  proof_hash CHAR(64) NOT NULL UNIQUE,
  customer_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  staff_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  draft_hash CHAR(64) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_order_id BIGINT UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX loyalty_scan_proofs_expiry ON promotion_service.loyalty_scan_proofs(expires_at);
