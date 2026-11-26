SET search_path TO catalog_service, public;

-- Convert user-facing text columns to JSONB {"vi": "...", "en": "..."} while keeping stores.address canonical for Mapbox geocoding.
ALTER TABLE catalog_service.stores
    ALTER COLUMN name TYPE JSONB USING jsonb_build_object('vi', name, 'en', name),
    ALTER COLUMN district TYPE JSONB USING CASE WHEN district IS NULL THEN NULL ELSE jsonb_build_object('vi', district, 'en', district) END,
    ALTER COLUMN city TYPE JSONB USING CASE WHEN city IS NULL THEN NULL ELSE jsonb_build_object('vi', city, 'en', city) END,
    ALTER COLUMN delivery_area TYPE JSONB USING CASE WHEN delivery_area IS NULL THEN NULL ELSE jsonb_build_object('vi', delivery_area, 'en', delivery_area) END;

ALTER TABLE catalog_service.categories
    ALTER COLUMN name TYPE JSONB USING jsonb_build_object('vi', name, 'en', name);

ALTER TABLE catalog_service.products
    ALTER COLUMN name TYPE JSONB USING jsonb_build_object('vi', name, 'en', name),
    ALTER COLUMN description TYPE JSONB USING jsonb_build_object('vi', description, 'en', description);

ALTER TABLE catalog_service.product_options
    ALTER COLUMN group_name TYPE JSONB USING jsonb_build_object('vi', group_name, 'en', group_name),
    ALTER COLUMN name TYPE JSONB USING jsonb_build_object('vi', name, 'en', name);

ALTER TABLE catalog_service.product_toppings
    ALTER COLUMN topping_name TYPE JSONB USING jsonb_build_object('vi', topping_name, 'en', topping_name);

ALTER TABLE catalog_service.combos
    ALTER COLUMN name TYPE JSONB USING jsonb_build_object('vi', name, 'en', name),
    ALTER COLUMN description TYPE JSONB USING jsonb_build_object('vi', description, 'en', description);

-- Populate clean English translations for seeded stores (only when en still equals vi)
UPDATE catalog_service.stores SET
    name = jsonb_set(name, '{en}', '"Clucky Le Loi (Flagship Store)"'::jsonb),
    district = CASE WHEN district->>'vi' = 'Quận 1' THEN jsonb_set(district, '{en}', '"District 1"'::jsonb) ELSE district END,
    city = CASE WHEN city->>'vi' = 'TP. Hồ Chí Minh' THEN jsonb_set(city, '{en}', '"Ho Chi Minh City"'::jsonb) ELSE city END,
    delivery_area = CASE WHEN delivery_area->>'vi' = 'Quận 1, Quận 3, Quận 4, Bình Thạnh (bán kính 5km)' THEN jsonb_set(delivery_area, '{en}', '"District 1, District 3, District 4, Binh Thanh (5km radius)"'::jsonb) ELSE delivery_area END
WHERE id = 1 AND name->>'vi' = 'Clucky Lê Lợi (Flagship Store)' AND name->>'en' = name->>'vi';

UPDATE catalog_service.stores SET
    name = jsonb_set(name, '{en}', '"Clucky Phan Van Tri"'::jsonb),
    district = CASE WHEN district->>'vi' = 'Gò Vấp' THEN jsonb_set(district, '{en}', '"Go Vap"'::jsonb) ELSE district END,
    city = CASE WHEN city->>'vi' = 'TP. Hồ Chí Minh' THEN jsonb_set(city, '{en}', '"Ho Chi Minh City"'::jsonb) ELSE city END,
    delivery_area = CASE WHEN delivery_area->>'vi' = 'Gò Vấp, Bình Thạnh, Phú Nhuận, Quận 12' THEN jsonb_set(delivery_area, '{en}', '"Go Vap, Binh Thanh, Phu Nhuan, District 12"'::jsonb) ELSE delivery_area END
WHERE id = 2 AND name->>'vi' = 'Clucky Phan Văn Trị' AND name->>'en' = name->>'vi';

UPDATE catalog_service.stores SET
    name = jsonb_set(name, '{en}', '"Clucky Nguyen Trai"'::jsonb),
    district = CASE WHEN district->>'vi' = 'Quận 5' THEN jsonb_set(district, '{en}', '"District 5"'::jsonb) ELSE district END,
    city = CASE WHEN city->>'vi' = 'TP. Hồ Chí Minh' THEN jsonb_set(city, '{en}', '"Ho Chi Minh City"'::jsonb) ELSE city END,
    delivery_area = CASE WHEN delivery_area->>'vi' = 'Quận 5, Quận 10, Quận 11, Quận 6 (bán kính 6km)' THEN jsonb_set(delivery_area, '{en}', '"District 5, District 10, District 11, District 6 (6km radius)"'::jsonb) ELSE delivery_area END
WHERE id = 3 AND name->>'vi' = 'Clucky Nguyễn Trãi' AND name->>'en' = name->>'vi';

UPDATE catalog_service.stores SET
    name = jsonb_set(name, '{en}', '"Clucky Le Van Viet (Thu Duc)"'::jsonb),
    district = CASE WHEN district->>'vi' = 'TP. Thủ Đức' THEN jsonb_set(district, '{en}', '"Thu Duc City"'::jsonb) ELSE district END,
    city = CASE WHEN city->>'vi' = 'TP. Hồ Chí Minh' THEN jsonb_set(city, '{en}', '"Ho Chi Minh City"'::jsonb) ELSE city END,
    delivery_area = CASE WHEN delivery_area->>'vi' = 'Khu công nghệ cao, Làng Đại học, TP. Thủ Đức, Quận 9' THEN jsonb_set(delivery_area, '{en}', '"Hi-Tech Park, University Village, Thu Duc City, District 9"'::jsonb) ELSE delivery_area END
WHERE id = 4 AND name->>'vi' = 'Clucky Lê Văn Việt (Thủ Đức)' AND name->>'en' = name->>'vi';

UPDATE catalog_service.stores SET
    name = jsonb_set(name, '{en}', '"Clucky Landmark 81 (Temporarily Closed)"'::jsonb),
    district = CASE WHEN district->>'vi' = 'Bình Thạnh' THEN jsonb_set(district, '{en}', '"Binh Thanh"'::jsonb) ELSE district END,
    city = CASE WHEN city->>'vi' = 'TP. Hồ Chí Minh' THEN jsonb_set(city, '{en}', '"Ho Chi Minh City"'::jsonb) ELSE city END,
    delivery_area = CASE WHEN delivery_area->>'vi' = 'Vinhomes Central Park, Bình Thạnh, Thảo Điền TP. Thủ Đức' THEN jsonb_set(delivery_area, '{en}', '"Vinhomes Central Park, Binh Thanh, Thao Dien Thu Duc City"'::jsonb) ELSE delivery_area END
WHERE id = 5 AND name->>'vi' = 'Clucky Landmark 81 (Tạm đóng bảo trì)' AND name->>'en' = name->>'vi';

-- Populate clean English translations for seeded categories
UPDATE catalog_service.categories SET name = jsonb_set(name, '{en}', '"Fried Chicken"'::jsonb)
WHERE id = 1 AND name->>'vi' = 'Gà rán' AND name->>'en' = name->>'vi';
UPDATE catalog_service.categories SET name = jsonb_set(name, '{en}', '"Burgers"'::jsonb)
WHERE id = 2 AND name->>'vi' = 'Burger' AND name->>'en' = name->>'vi';
UPDATE catalog_service.categories SET name = jsonb_set(name, '{en}', '"Combos"'::jsonb)
WHERE id = 3 AND name->>'vi' = 'Combo' AND name->>'en' = name->>'vi';
UPDATE catalog_service.categories SET name = jsonb_set(name, '{en}', '"Sides"'::jsonb)
WHERE id = 4 AND name->>'vi' = 'Món phụ' AND name->>'en' = name->>'vi';
UPDATE catalog_service.categories SET name = jsonb_set(name, '{en}', '"Drinks"'::jsonb)
WHERE id = 5 AND name->>'vi' = 'Đồ uống' AND name->>'en' = name->>'vi';

-- Populate clean English translations for seeded products
UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Classic Crispy Fried Chicken"'::jsonb),
    description = jsonb_set(description, '{en}', '"Fresh chicken marinated in our signature 12-spice blend, pressure-fried to golden perfection — crispy outside and juicy inside."'::jsonb)
WHERE id = 1 AND name->>'vi' = 'Gà rán giòn truyền thống' AND name->>'en' = name->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Spicy Honey Glazed Wings"'::jsonb),
    description = jsonb_set(description, '{en}', '"Crispy fried chicken wings glazed in mild chili and fragrant wild honey, finished with toasted sesame seeds."'::jsonb)
WHERE id = 2 AND name->>'vi' = 'Cánh gà cay sốt mật ong' AND name->>'en' = name->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Clucky Crispy Burger"'::jsonb),
    description = jsonb_set(description, '{en}', '"Fluffy brioche bun layered with a jumbo crispy chicken breast, red cabbage, pickles, and truffle mayonnaise."'::jsonb)
WHERE id = 3 AND name->>'vi' = 'Clucky Crispy Burger' AND description->>'en' = description->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Melted Cheese Chicken Burger"'::jsonb),
    description = jsonb_set(description, '{en}', '"Golden-fried chicken fillet topped with double melted Cheddar slices and rich warm cheese sauce."'::jsonb)
WHERE id = 4 AND name->>'vi' = 'Burger phô mai gà tan chảy' AND name->>'en' = name->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Sea Salt French Fries"'::jsonb),
    description = jsonb_set(description, '{en}', '"Thick-cut imported potatoes fried until golden and crispy outside, fluffy and tender inside, dusted with sea salt."'::jsonb)
WHERE id = 5 AND name->>'vi' = 'Khoai tây chiên muối biển' AND name->>'en' = name->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Peach Orange Lemongrass Iced Tea"'::jsonb),
    description = jsonb_set(description, '{en}', '"Cold-brewed premium oolong tea blended with fresh orange essence, aromatic lemongrass, and crisp peach slices."'::jsonb)
WHERE id = 6 AND name->>'vi' = 'Trà đào cam sả thanh nhiệt' AND name->>'en' = name->>'vi';

UPDATE catalog_service.products SET
    name = jsonb_set(name, '{en}', '"Fiery Habanero Hot Chicken"'::jsonb),
    description = jsonb_set(description, '{en}', '"Crafted for true spice lovers. Explosive Habanero chili glaze with an irresistibly savory, smoky finish."'::jsonb)
WHERE id = 7 AND name->>'vi' = 'Gà sốt cay lửa Habanero' AND name->>'en' = name->>'vi';

-- Populate clean English translations for seeded product options
UPDATE catalog_service.product_options SET group_name = jsonb_set(group_name, '{en}', '"Size"'::jsonb)
WHERE group_name->>'vi' = 'Kích thước' AND group_name->>'en' = group_name->>'vi';
UPDATE catalog_service.product_options SET group_name = jsonb_set(group_name, '{en}', '"Spice Level"'::jsonb)
WHERE group_name->>'vi' = 'Mức cay theo yêu cầu' AND group_name->>'en' = group_name->>'vi';
UPDATE catalog_service.product_options SET group_name = jsonb_set(group_name, '{en}', '"Bun Type"'::jsonb)
WHERE group_name->>'vi' = 'Loại bánh' AND group_name->>'en' = group_name->>'vi';
UPDATE catalog_service.product_options SET group_name = jsonb_set(group_name, '{en}', '"Ice Level"'::jsonb)
WHERE group_name->>'vi' = 'Độ đá' AND group_name->>'en' = group_name->>'vi';
UPDATE catalog_service.product_options SET group_name = jsonb_set(group_name, '{en}', '"Sweetness"'::jsonb)
WHERE group_name->>'vi' = 'Độ ngọt' AND group_name->>'en' = group_name->>'vi';

UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Regular (Medium)"'::jsonb)
WHERE name->>'vi' = 'Tiêu chuẩn (Vừa)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Large (+30% Chicken)"'::jsonb)
WHERE name->>'vi' = 'Lớn (Thêm 30% thịt)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Non-Spicy (Savory)"'::jsonb)
WHERE name->>'vi' = 'Không cay (Ngọt thơm)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Medium Spice (Standard)"'::jsonb)
WHERE name->>'vi' = 'Cay vừa (Tiêu chuẩn)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Extra Hot (Habanero)"'::jsonb)
WHERE name->>'vi' = 'Siêu cay (Ớt Habanero)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"French Butter Brioche"'::jsonb)
WHERE name->>'vi' = 'Brioche bơ Pháp' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"Classic Sesame Bun"'::jsonb)
WHERE name->>'vi' = 'Vỏ mè truyền thống' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"100% Ice (Regular)"'::jsonb)
WHERE name->>'vi' = '100% đá (Tiêu chuẩn)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"50% Ice"'::jsonb)
WHERE name->>'vi' = '50% đá' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"No Ice"'::jsonb)
WHERE name->>'vi' = 'Không đá' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"100% Sugar (Regular)"'::jsonb)
WHERE name->>'vi' = '100% đường (Vừa miệng)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"50% Sugar (Less Sweet)"'::jsonb)
WHERE name->>'vi' = '50% đường (Ít ngọt)' AND name->>'en' = name->>'vi';
UPDATE catalog_service.product_options SET name = jsonb_set(name, '{en}', '"No Added Sugar"'::jsonb)
WHERE name->>'vi' = 'Không thêm đường' AND name->>'en' = name->>'vi';

-- Populate clean English translations for seeded toppings
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Premium Spicy Cheese Sauce"'::jsonb)
WHERE topping_name->>'vi' = 'Sốt phô mai cay hảo hạng' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Mashed Potatoes with Gravy"'::jsonb)
WHERE topping_name->>'vi' = 'Khoai tây nghiền sốt gravy' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Snow Cheese Powder"'::jsonb)
WHERE topping_name->>'vi' = 'Bột phô mai tuyết phủ giòn' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Runny Fried Egg"'::jsonb)
WHERE topping_name->>'vi' = 'Trứng ốp la lòng đào' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Extra Cheddar Cheese Slice"'::jsonb)
WHERE topping_name->>'vi' = 'Phô mai Cheddar lát thêm' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Seared Crispy Bacon"'::jsonb)
WHERE topping_name->>'vi' = 'Thịt xông khói áp chảo' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Seaweed Shaker Seasoning"'::jsonb)
WHERE topping_name->>'vi' = 'Bột rong biển lắc' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Snow Cheese Seasoning"'::jsonb)
WHERE topping_name->>'vi' = 'Bột phô mai tuyết' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Extra 2 Peach Slices"'::jsonb)
WHERE topping_name->>'vi' = 'Thêm 2 lát đào giòn' AND topping_name->>'en' = topping_name->>'vi';
UPDATE catalog_service.product_toppings SET topping_name = jsonb_set(topping_name, '{en}', '"Organic Chia Seeds"'::jsonb)
WHERE topping_name->>'vi' = 'Hạt chia organic' AND topping_name->>'en' = topping_name->>'vi';

-- Populate clean English translations for seeded combos
UPDATE catalog_service.combos SET
    description = jsonb_set(description, '{en}', '"1 Crispy Chicken Drumstick + 1 Golden French Fries + 1 Refreshing Soft Drink."'::jsonb)
WHERE id = 1 AND name->>'vi' = 'Combo Clucky Classic' AND description->>'en' = description->>'vi';

UPDATE catalog_service.combos SET
    description = jsonb_set(description, '{en}', '"Family Feast: 4 Fried Chicken Pieces + 2 Clucky Burgers + 2 French Fries + 4 Soft Drinks."'::jsonb)
WHERE id = 2 AND name->>'vi' = 'Combo Family Feast' AND description->>'en' = description->>'vi';
