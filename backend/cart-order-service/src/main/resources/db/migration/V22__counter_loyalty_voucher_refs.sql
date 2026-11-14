ALTER TABLE order_service.orders
  ADD COLUMN loyalty_wallet_id BIGINT,
  ADD COLUMN loyalty_subject_type VARCHAR(20),
  ADD COLUMN loyalty_policy_version BIGINT,
  ADD COLUMN loyalty_auth_method VARCHAR(20),
  ADD COLUMN loyalty_scan_proof_id VARCHAR(80),
  ADD COLUMN points_to_earn BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN customer_voucher_instance_id UUID,
  ADD COLUMN voucher_campaign_id BIGINT,
  ADD COLUMN voucher_discount_amount NUMERIC(12,0) NOT NULL DEFAULT 0;
CREATE INDEX counter_loyalty_wallet_idx ON order_service.orders(loyalty_wallet_id)
  WHERE loyalty_wallet_id IS NOT NULL;
