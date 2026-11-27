CREATE TABLE IF NOT EXISTS review_report_service.daily_item_stats (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    item_ref_id BIGINT NOT NULL,
    item_name_i18n JSONB NOT NULL,
    quantity_sold INT NOT NULL DEFAULT 0,
    order_count INT NOT NULL DEFAULT 0,
    gross_revenue NUMERIC(12, 0) NOT NULL DEFAULT 0,
    allocated_net_revenue NUMERIC(12, 0) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_daily_item_stats UNIQUE (store_id, stat_date, item_type, item_ref_id)
);

CREATE INDEX IF NOT EXISTS idx_daily_item_stats_date_store
    ON review_report_service.daily_item_stats (stat_date, store_id);

CREATE INDEX IF NOT EXISTS idx_daily_item_stats_ref
    ON review_report_service.daily_item_stats (item_type, item_ref_id, stat_date);

ALTER TABLE review_report_service.daily_store_stats
    ADD COLUMN IF NOT EXISTS cancelled_order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cancelled_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_discount_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_points_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS delivery_order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pickup_order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS web_order_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS counter_order_count INT NOT NULL DEFAULT 0;

ALTER TABLE review_report_service.order_projection_trackers
    ADD COLUMN IF NOT EXISTS items_contributed BOOLEAN NOT NULL DEFAULT FALSE;
