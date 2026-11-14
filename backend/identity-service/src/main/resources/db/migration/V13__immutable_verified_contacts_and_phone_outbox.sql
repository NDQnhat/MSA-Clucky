-- A phone binding version identifies the verified contact for idempotent Loyalty claims.
ALTER TABLE identity_service.users
  ADD COLUMN phone_binding_version BIGINT NOT NULL DEFAULT 0;

UPDATE identity_service.users
SET phone_binding_version = 1
WHERE role = 'CUSTOMER' AND phone_verified AND phone IS NOT NULL;

-- Guard legacy writes, bulk SQL and any future endpoint that bypasses profile APIs.
-- Branch-account recovery emails follow their own management contract.
CREATE OR REPLACE FUNCTION identity_service.protect_verified_customer_contacts()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.role = 'CUSTOMER' THEN
    IF OLD.phone_verified AND
       (NEW.phone IS DISTINCT FROM OLD.phone OR NEW.phone_verified IS DISTINCT FROM TRUE
        OR NEW.phone_binding_version IS DISTINCT FROM OLD.phone_binding_version) THEN
      RAISE EXCEPTION 'Verified customer phone cannot be changed' USING ERRCODE = '23514';
    END IF;
    IF OLD.email_verified AND
       (NEW.email IS DISTINCT FROM OLD.email OR NEW.email_verified IS DISTINCT FROM TRUE) THEN
      RAISE EXCEPTION 'Verified customer email cannot be changed' USING ERRCODE = '23514';
    END IF;
    IF NEW.phone_binding_version < OLD.phone_binding_version THEN
      RAISE EXCEPTION 'Phone binding version cannot decrease' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER users_verified_customer_contact_guard
BEFORE UPDATE ON identity_service.users
FOR EACH ROW EXECUTE FUNCTION identity_service.protect_verified_customer_contacts();

CREATE TABLE identity_service.customer_phone_verified_outbox (
  event_id UUID PRIMARY KEY,
  customer_id BIGINT NOT NULL REFERENCES identity_service.users(id),
  binding_version BIGINT NOT NULL CHECK (binding_version > 0),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  UNIQUE(customer_id, binding_version)
);

CREATE INDEX customer_phone_verified_outbox_pending
  ON identity_service.customer_phone_verified_outbox(occurred_at)
  WHERE published_at IS NULL;

-- Existing verified customers can claim guest points after deployment too.
INSERT INTO identity_service.customer_phone_verified_outbox(event_id,customer_id,binding_version)
SELECT gen_random_uuid(), id, phone_binding_version
FROM identity_service.users
WHERE role='CUSTOMER' AND phone_verified AND phone IS NOT NULL
  AND phone_binding_version > 0;
