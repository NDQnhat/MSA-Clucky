SET search_path TO order_service, public;

ALTER TABLE order_service.order_items
    ALTER COLUMN item_name TYPE JSONB
    USING jsonb_build_object('vi', item_name, 'en', item_name);

UPDATE order_service.order_items
SET item_name = CASE item_name->>'vi'
    WHEN 'Gà rán giòn truyền thống' THEN jsonb_build_object('vi', 'Gà rán giòn truyền thống', 'en', 'Original Crispy Fried Chicken')
    WHEN 'Cánh gà cay sốt mật ong' THEN jsonb_build_object('vi', 'Cánh gà cay sốt mật ong', 'en', 'Spicy Honey Glazed Wings')
    WHEN 'Clucky Crispy Burger' THEN jsonb_build_object('vi', 'Clucky Crispy Burger', 'en', 'Clucky Crispy Burger')
    WHEN 'Burger phô mai gà tan chảy' THEN jsonb_build_object('vi', 'Burger phô mai gà tan chảy', 'en', 'Cheesy Chicken Melt Burger')
    WHEN 'Khoai tây chiên muối biển' THEN jsonb_build_object('vi', 'Khoai tây chiên muối biển', 'en', 'Sea Salt French Fries')
    WHEN 'Trà đào cam sả thanh nhiệt' THEN jsonb_build_object('vi', 'Trà đào cam sả thanh nhiệt', 'en', 'Peach Orange Lemongrass Tea')
    WHEN 'Gà sốt cay lửa Habanero' THEN jsonb_build_object('vi', 'Gà sốt cay lửa Habanero', 'en', 'Fiery Habanero Glazed Chicken')
    WHEN 'Combo Clucky Classic' THEN jsonb_build_object('vi', 'Combo Clucky Classic', 'en', 'Clucky Classic Combo')
    WHEN 'Combo Family Feast' THEN jsonb_build_object('vi', 'Combo Family Feast', 'en', 'Family Feast Combo')
    ELSE item_name
END
WHERE item_name->>'vi' = item_name->>'en';

-- Add bilingual fields inside seeded counter order option/topping snapshots
UPDATE order_service.order_items
SET options_snapshot = (
    SELECT COALESCE(jsonb_agg(
        elem
        || jsonb_build_object(
            'groupNameI18n',
            CASE elem->>'groupName'
                WHEN 'Mức cay' THEN jsonb_build_object('vi', 'Mức cay', 'en', 'Spice Level')
                WHEN 'Kích thước' THEN jsonb_build_object('vi', 'Kích thước', 'en', 'Size')
                WHEN 'Loại bánh' THEN jsonb_build_object('vi', 'Loại bánh', 'en', 'Bun Type')
                WHEN 'Độ ngọt' THEN jsonb_build_object('vi', 'Độ ngọt', 'en', 'Sweetness')
                WHEN 'Đổi nước' THEN jsonb_build_object('vi', 'Đổi nước', 'en', 'Drink Choice')
                WHEN 'Đổi món phụ' THEN jsonb_build_object('vi', 'Đổi món phụ', 'en', 'Side Dish')
                ELSE jsonb_build_object('vi', COALESCE(elem->>'groupName', ''), 'en', COALESCE(elem->>'groupName', ''))
            END,
            'nameI18n',
            CASE elem->>'name'
                WHEN 'Cay vừa' THEN jsonb_build_object('vi', 'Cay vừa', 'en', 'Medium Spicy')
                WHEN 'Siêu cay' THEN jsonb_build_object('vi', 'Siêu cay', 'en', 'Extra Spicy')
                WHEN 'Tiêu chuẩn' THEN jsonb_build_object('vi', 'Tiêu chuẩn', 'en', 'Standard')
                WHEN 'Lớn (Thêm 30% thịt)' THEN jsonb_build_object('vi', 'Lớn (Thêm 30% thịt)', 'en', 'Large (+30% Meat)')
                WHEN 'Brioche bơ Pháp' THEN jsonb_build_object('vi', 'Brioche bơ Pháp', 'en', 'French Butter Brioche')
                WHEN '50% đường' THEN jsonb_build_object('vi', '50% đường', 'en', '50% Sugar')
                WHEN '7Up' THEN jsonb_build_object('vi', '7Up', 'en', '7Up')
                WHEN 'Bắp cải trộn' THEN jsonb_build_object('vi', 'Bắp cải trộn', 'en', 'Coleslaw')
                ELSE jsonb_build_object('vi', COALESCE(elem->>'name', ''), 'en', COALESCE(elem->>'name', ''))
            END
        )
    ), '[]'::jsonb)
    FROM jsonb_array_elements(options_snapshot) AS elem
)
WHERE options_snapshot IS NOT NULL
  AND jsonb_typeof(options_snapshot) = 'array'
  AND jsonb_array_length(options_snapshot) > 0;

UPDATE order_service.order_items
SET toppings_snapshot = (
    SELECT COALESCE(jsonb_agg(
        elem
        || jsonb_build_object(
            'nameI18n',
            CASE elem->>'name'
                WHEN 'Sốt phô mai cay' THEN jsonb_build_object('vi', 'Sốt phô mai cay', 'en', 'Spicy Cheese Sauce')
                WHEN 'Phô mai Cheddar thêm' THEN jsonb_build_object('vi', 'Phô mai Cheddar thêm', 'en', 'Extra Cheddar Cheese')
                WHEN 'Bột rong biển lắc' THEN jsonb_build_object('vi', 'Bột rong biển lắc', 'en', 'Crispy Seaweed Shaker')
                ELSE jsonb_build_object('vi', COALESCE(elem->>'name', ''), 'en', COALESCE(elem->>'name', ''))
            END
        )
    ), '[]'::jsonb)
    FROM jsonb_array_elements(toppings_snapshot) AS elem
)
WHERE toppings_snapshot IS NOT NULL
  AND jsonb_typeof(toppings_snapshot) = 'array'
  AND jsonb_array_length(toppings_snapshot) > 0;
