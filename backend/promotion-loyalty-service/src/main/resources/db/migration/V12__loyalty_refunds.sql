-- Refunds are append-only operations. Never rewrite historical earned/redeemed entries.
-- A debt represents earned points already spent before their originating order was refunded.
ALTER TABLE promotion_service.member_points
  ADD COLUMN pending_clawback_points BIGINT NOT NULL DEFAULT 0
    CHECK (pending_clawback_points >= 0);

CREATE TABLE promotion_service.loyalty_refunds (
  refund_id BIGINT PRIMARY KEY CHECK (refund_id > 0),
  order_id BIGINT NOT NULL UNIQUE CHECK (order_id > 0),
  refund_amount NUMERIC(12,0) NOT NULL CHECK (refund_amount > 0),
  wallet_id BIGINT REFERENCES promotion_service.member_points(id),
  original_wallet_id BIGINT REFERENCES promotion_service.member_points(id),
  state VARCHAR(20) NOT NULL CHECK (state IN ('PENDING','NOOP','COMPENSATED')),
  redeemed_restored BIGINT NOT NULL DEFAULT 0 CHECK (redeemed_restored >= 0),
  earned_reversed BIGINT NOT NULL DEFAULT 0 CHECK (earned_reversed >= 0),
  earned_debited BIGINT NOT NULL DEFAULT 0 CHECK (earned_debited >= 0),
  clawback_deferred BIGINT NOT NULL DEFAULT 0 CHECK (clawback_deferred >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);
CREATE INDEX loyalty_refunds_wallet_idx ON promotion_service.loyalty_refunds(wallet_id,created_at DESC);
