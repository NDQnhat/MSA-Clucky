# Chính sách giao hàng và chi nhánh Clucky

## Phí giao hàng và bán kính phục vụ
- **Đơn nhận tại cửa hàng (`PICKUP`)**: Phí giao hàng luôn là **0đ**.
- **Đơn giao tận nơi (`DELIVERY`)**:
  - Áp dụng **phí giao hàng chuẩn toàn chuỗi** (mặc định **15.000đ**, có thể được quản trị viên điều chỉnh theo chính sách hiện hành).
  - Hệ thống kiểm tra tọa độ địa chỉ giao hàng của bạn so với vị trí và **bán kính giao hàng (`deliveryRadiusKm`)** của chi nhánh đã chọn. Nếu vượt quá bán kính phục vụ của chi nhánh đó, hệ thống sẽ thông báo ngoài phạm vi giao hàng và gợi ý bạn chọn chi nhánh gần hơn hoặc chuyển sang hình thức `PICKUP`.

## Chi nhánh và giờ mở cửa
- Mỗi chi nhánh Clucky có khung giờ mở cửa riêng (`openTime` - `closeTime`) và trạng thái vận hành (`OPEN`, `CLOSED_TEMP`, `INACTIVE`).
- Chỉ những chi nhánh ở trạng thái **`OPEN`** và đang trong giờ phục vụ mới nhận đơn trực tuyến.
- Thực đơn, giá tại chi nhánh (`storePrice`) và tình trạng còn món/hết món (`isSoldOut`) được quản lý riêng theo từng chi nhánh.
