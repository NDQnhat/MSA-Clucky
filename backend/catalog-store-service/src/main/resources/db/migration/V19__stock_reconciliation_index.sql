CREATE INDEX IF NOT EXISTS idx_stock_ledger_reconcile
    ON catalog_service.order_stock_ledger (status, updated_at)
    WHERE status = 'DEDUCTED';
