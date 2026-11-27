SET search_path TO catalog_service, public;

ALTER TABLE catalog_service.products
    ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_products_tags_gin
    ON catalog_service.products USING GIN (tags);

UPDATE catalog_service.products SET tags = '["crispy", "signature", "best-seller"]'::jsonb
WHERE id = 1 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["crispy", "honey", "spicy"]'::jsonb
WHERE id = 2 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["burger", "crispy"]'::jsonb
WHERE id = 3 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["burger", "cheese", "kid-friendly"]'::jsonb
WHERE id = 4 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["crispy", "snack", "kid-friendly", "light-meal"]'::jsonb
WHERE id = 5 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["cold", "drink", "summer", "refreshing", "light-meal"]'::jsonb
WHERE id = 6 AND tags = '[]'::jsonb;

UPDATE catalog_service.products SET tags = '["spicy", "hot", "crispy"]'::jsonb
WHERE id = 7 AND tags = '[]'::jsonb;
