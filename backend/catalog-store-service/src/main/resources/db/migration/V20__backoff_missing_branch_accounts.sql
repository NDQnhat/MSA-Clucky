ALTER TABLE catalog_service.stores
    ADD COLUMN identity_not_found_at TIMESTAMPTZ;

CREATE INDEX idx_stores_branch_identity_recheck
    ON catalog_service.stores (account_readiness, identity_not_found_at);
