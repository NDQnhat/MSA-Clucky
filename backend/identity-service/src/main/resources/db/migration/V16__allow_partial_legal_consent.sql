-- Permit acceptance of a single published policy without touching earlier
-- V15 migrations or rewriting historical consent records.
ALTER TABLE identity_service.legal_consents
  ALTER COLUMN terms_revision_id DROP NOT NULL,
  ALTER COLUMN privacy_revision_id DROP NOT NULL,
  ADD CONSTRAINT legal_consents_at_least_one_revision
    CHECK (terms_revision_id IS NOT NULL OR privacy_revision_id IS NOT NULL);
