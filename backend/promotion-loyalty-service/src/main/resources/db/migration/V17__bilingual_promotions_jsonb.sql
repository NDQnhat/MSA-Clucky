SET search_path TO promotion_service, public;

ALTER TABLE promotion_service.vouchers
    ALTER COLUMN title TYPE JSONB USING CASE WHEN title IS NULL THEN NULL ELSE jsonb_build_object('vi', title, 'en', title) END,
    ALTER COLUMN description TYPE JSONB USING CASE WHEN description IS NULL THEN NULL ELSE jsonb_build_object('vi', description, 'en', description) END;

ALTER TABLE promotion_service.banners
    ALTER COLUMN title TYPE JSONB USING jsonb_build_object('vi', title, 'en', title);

-- Populate clean English translations for seeded vouchers (only when en still equals vi)
UPDATE promotion_service.vouchers SET
    title = jsonb_set(title, '{en}', '"20,000đ off orders from 99,000đ"'::jsonb),
    description = jsonb_set(description, '{en}', '"Applicable across the entire menu at all Clucky branches."'::jsonb)
WHERE code = 'CLUCKYGIAM20' AND title->>'vi' = 'Giảm 20.000đ cho đơn từ 99.000đ' AND title->>'en' = title->>'vi';

UPDATE promotion_service.vouchers SET
    title = jsonb_set(title, '{en}', '"20,000đ Free Delivery"'::jsonb),
    description = jsonb_set(description, '{en}', '"Free delivery for delivery orders starting from 150,000đ."'::jsonb)
WHERE code = 'CLUCKYFREESHIP' AND title->>'vi' = 'Miễn phí vận chuyển 20.000đ' AND title->>'en' = title->>'vi';

UPDATE promotion_service.vouchers SET
    title = jsonb_set(title, '{en}', '"15% off up to 50,000đ"'::jsonb),
    description = jsonb_set(description, '{en}', '"Enjoy fiery glazed chicken with 15% off your total bill."'::jsonb)
WHERE code = 'CLUCKYPROMO15' AND title->>'vi' = 'Giảm 15% tối đa 50.000đ' AND title->>'en' = title->>'vi';

UPDATE promotion_service.vouchers SET
    title = jsonb_set(title, '{en}', '"30,000đ off at Clucky District 1"'::jsonb),
    description = jsonb_set(description, '{en}', '"Exclusive perk for dine-in or online orders at our District 1 branch."'::jsonb)
WHERE code = 'CLUCKYQ1VIP' AND title->>'vi' = 'Giảm 30.000đ tại Clucky Quận 1' AND title->>'en' = title->>'vi';
