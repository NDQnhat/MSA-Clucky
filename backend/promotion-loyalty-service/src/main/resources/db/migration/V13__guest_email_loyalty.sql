-- Guest emails are never stored in the loyalty database. The HMAC is
-- domain-separated from phone keys and uses the existing shared private key.
ALTER TABLE promotion_service.member_points
  ADD COLUMN email_key CHAR(64);

ALTER TABLE promotion_service.member_points
  DROP CONSTRAINT wallet_owner_check;
ALTER TABLE promotion_service.member_points
  ADD CONSTRAINT wallet_owner_check CHECK (
    (owner_type='CUSTOMER' AND customer_id IS NOT NULL AND phone_key IS NULL AND email_key IS NULL)
    OR (owner_type='PHONE' AND customer_id IS NULL AND phone_key IS NOT NULL
        AND phone_key_version IS NOT NULL AND email_key IS NULL)
    OR (owner_type='EMAIL' AND customer_id IS NULL AND email_key IS NOT NULL
        AND phone_key IS NULL AND phone_key_version IS NULL)
  );

CREATE UNIQUE INDEX wallet_email_key_unique ON promotion_service.member_points(email_key)
  WHERE owner_type='EMAIL';

CREATE TABLE promotion_service.loyalty_verified_emails (
  email_key CHAR(64) PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE promotion_service.loyalty_email_claims (
  source_wallet_id BIGINT PRIMARY KEY REFERENCES promotion_service.member_points(id),
  target_wallet_id BIGINT NOT NULL REFERENCES promotion_service.member_points(id),
  verified_customer_id BIGINT NOT NULL,
  amount BIGINT NOT NULL CHECK (amount >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
