-- A parallel branch assigned V25 to pricing snapshots. Preserve that history
-- and let Flyway apply the canonical V25 normally after V24.
DO $migration$
DECLARE
    legacy_rank integer;
BEGIN
    IF to_regclass('order_service.flyway_schema_history') IS NULL THEN
        RETURN;
    END IF;

    SELECT installed_rank INTO legacy_rank
    FROM order_service.flyway_schema_history
    WHERE version = '25' AND script = 'V25__sprint4_order_pricing_snapshots.sql'
      AND checksum = -1247386222 AND type = 'SQL' AND success;
    IF legacy_rank IS NULL THEN
        RETURN;
    END IF;

    LOCK TABLE order_service.flyway_schema_history IN SHARE ROW EXCLUSIVE MODE;
    IF NOT EXISTS (
        SELECT 1 FROM order_service.flyway_schema_history
        WHERE installed_rank = legacy_rank AND version = '25'
          AND script = 'V25__sprint4_order_pricing_snapshots.sql'
          AND checksum = -1247386222 AND type = 'SQL' AND success
    ) THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM order_service.flyway_schema_history
        WHERE version = '24' AND script = 'V24__web_order_idempotency.sql'
          AND checksum = 1023483375 AND type = 'SQL' AND success
    ) OR (SELECT count(*) FROM order_service.flyway_schema_history WHERE version = '25') <> 1
      OR EXISTS (SELECT 1 FROM order_service.flyway_schema_history WHERE installed_rank > legacy_rank)
      OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'order_service' AND table_name = 'orders' AND column_name = 'counter_payment_started'
    ) THEN
        RAISE EXCEPTION 'Legacy order V25 does not match the expected upgrade baseline';
    END IF;

    CREATE TABLE IF NOT EXISTS order_service.flyway_legacy_history
        (LIKE order_service.flyway_schema_history INCLUDING ALL);
    WITH retired AS (
        DELETE FROM order_service.flyway_schema_history WHERE installed_rank = legacy_rank RETURNING *
    )
    INSERT INTO order_service.flyway_legacy_history SELECT * FROM retired;
END;
$migration$;
