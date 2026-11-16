## BE-88 — Đơn sau voucher/điểm còn 0đ vẫn đặt được, bỏ QR và xử lý trọn vòng đời

**Phụ trách:** Chưa phân công · **Sprint:** 4 · **Ước lượng đề xuất:** 5 SP (8 giờ)
**Phụ thuộc:** Quote voucher/điểm ở Promotion–Loyalty; checkout Customer/POS; S4-BE-87; Order events, Shipping mock, Notification và Report.
**Ưu tiên đề xuất:** 1/5 · **Phạm vi:** Backend / Order + Payment + consumer/event contracts, phối hợp hai frontend.

### 1. User Story

- **Là Customer**, khi voucher hoặc điểm làm tổng đơn về 0đ, tôi muốn đặt hàng thành công kể cả đã chọn chuyển khoản/VietQR trước đó.
- **Là Staff**, tôi muốn đơn POS miễn thanh toán được xác nhận theo quy tắc Order mà không phải thu 0đ hoặc bấm xác nhận QR giả.
- **Là người vận hành**, tôi muốn đơn 0đ đi qua chuẩn bị, bàn giao, giao hàng giả lập, email và báo cáo bình thường: số đơn đúng nhưng doanh thu thu thực tế bằng 0đ.

### 2. Công việc

- Giữ **backend quote cuối** làm nguồn duy nhất quyết định `totalAmount` sau giá, voucher, điểm và phí giao hàng. Kiểm tra áp dụng voucher/điểm, `storeId`, inventory, trạng thái Order, hạn ưu đãi và quote version tại thời điểm submit; UI không được tự khai `totalAmount=0` để né thanh toán.
- Rà `OrderService.createOrder` và `createCounterOrder`: source ngày 15/11 đã đổi web 0đ thành effective `paymentMethod=COD`, POS 0đ thành effective `CASH`, đồng thời ghi `collectionMethod=NO_PAYMENT` và `paymentStatus=COLLECTED`; xác nhận contract được dùng nhất quán, không buộc gán `paymentMethod=NO_PAYMENT` nếu enum đó không hỗ trợ.
- Với Customer web 0đ, khởi tạo Order `CONFIRMED`, không `PENDING_PAYMENT`; với POS duy trì state Order phù hợp quầy và sẵn sàng xử lý. Các giá trị `paidAmount`/`collectedAmount`/bank/cash bằng 0 theo schema, **không** chuyển thành chứng cứ có tiền.
- Chặn tạo `/payment-session`, `/counter-payment-session`, VietQR, SePay transfer và `/counter-collection` khi amount phải thu bằng 0. Payment guard đối với giao dịch amount ≤ 0 vẫn áp dụng; xử lý miễn thu nằm ở Order, không tạo Payment 0đ hoặc `payment.confirmed` giả.
- Đảm bảo `OrderEventPublisher.buildOrderPayload` phát **hai trường tách biệt**: `paymentMethod` là phương thức Order hiệu lực; `collectionMethod=NO_PAYMENT` là nguồn trạng thái miễn thu. Consumer không suy luận `NO_PAYMENT` từ field sai hoặc coi đây là ngân hàng đã thanh toán.
- Kiểm tra Shipping: `ShipmentServiceTemplate.snapshot`, `MockDeliveryEngine` chấp nhận 0đ đi qua READY → pickup → delivery theo MVP; không giả lập trừ ví shipper, hoàn tiền COD, thanh toán QR, ứng tiền hoặc tracking/assignment Phase 2.
- Kiểm Promotion–Loyalty: voucher reserve rồi settle khi đơn hợp lệ hoàn thành, release khi hủy theo contract; điểm redeem không bị ghi trùng khi retry, điểm earn chỉ theo doanh số hợp lệ và không tạo điểm từ doanh thu 0; tồn kho/event bền theo outbox, consumer idempotent.
- Kiểm Report/Notification/email: `order.completed` chỉ tăng `order_count` một lần, doanh thu thu thực tế +0đ; email hiển thị **“Đơn hàng miễn thanh toán”**, không “đã chuyển khoản”, “chờ đối soát” hay “thu tiền tại quầy”. Trạng thái đơn và thông báo đúng cả web, nhân sự.
- Rà idempotency cả hai flow: Customer `POST /api/orders` có `Idempotency-Key` bắt buộc với fingerprint và lưu bền ở `web_order_operations` (Flyway `V24__web_order_idempotency.sql`); POS có `counter_order_operations`. Retry/click đôi/response thất lạc trả lại cùng orderId, key + payload khác conflict.
- Đồng bộ DTO `OrderResponse.collectionMethod`, UI Next.js checkout/order details và React `CounterOrderModal`: hiển thị “Miễn thanh toán — 0đ”, ẩn QR/thu tiền; khi quote đổi thành số dương (kể cả **1đ**) phục hồi phương thức thanh toán được phép, không giữ nhãn 0đ cũ.
- Rà race khi thay giỏ/voucher/điểm sau preview, phương thức QR đã chọn rồi đổi giảm giá, tab cũ submit sau checkout mới; backend tính lại và trả conflict/quote mới khi dữ liệu bất nhất, tránh xác nhận tiền sai.
- Đối chiếu migrations mới, event version/consumer và báo cáo sau replay; không sửa migration đã chạy. Ghi tình trạng code đã triển khai local theo kế hoạch nguồn và phân biệt rõ phần nghiệm thu thực tế còn phải làm.

### 3. API

| API / trạng thái | Quyền, dữ liệu và quy tắc |
| --- | --- |
| **Hiện có** `POST /api/orders/preview` | Customer/guest theo contract checkout; tính lại quote ưu đãi/ship từ server, chưa tạo Order hay yêu cầu thanh toán. |
| **Hiện có** `POST /api/orders` | Customer theo ownership; header `Idempotency-Key` bắt buộc; dù request chọn `BANK_TRANSFER` thì 0đ tạo Order `NO_PAYMENT/COLLECTED`, không QR. |
| **Hiện có** `POST /api/orders/counter/preview` | Staff/Manager/ADMIN đúng store; quote cho món/voucher/điểm/loyalty tại quầy; không cấp quyền qua body `storeId`. |
| **Hiện có** `POST /api/orders/counter` | Nhân sự đúng store; `Idempotency-Key` theo draft; tạo POS 0đ miễn thu ngay tại Order. |
| **Hiện có** `GET /api/orders/{id}`, `GET /api/orders/my` | Chỉ người có ownership/role/store; trả `paymentStatus` và `collectionMethod` để UI không suy đoán từ paymentMethod. |
| **Hiện có** `POST /api/orders/{id}/payment-session`, `GET /api/orders/{id}/payment-session`, `POST /api/orders/{id}/counter-payment-session` | Chỉ mở/đọc session theo state, ownership, số **dương**; 0đ không tạo session/QR, phản hồi miễn thu theo contract. |
| **Hiện có** `POST /api/payments/{orderId}/bank-transfer`, `/counter-bank-transfer`, `POST /api/payments/counter-collection` | Payment từ chối thu tạo 0đ; web/customer không được dùng đường nhân sự. |
| **Hiện có** `PUT /api/orders/{id}/status`, `PUT /api/orders/{id}/cancel` | Theo state machine và actor; hoàn thành/hủy 0đ phát đúng event settle/release. |
| **Hợp đồng event đã triển khai local, cần nghiệm thu end-to-end** `order.created/status-changed/completed/cancelled` | Producer tách `paymentMethod` khỏi `collectionMethod`, consumer Shipping/Report/Notification/Promotion xử lý zero đúng, dedup event. |
| **Không đề xuất API mới** “pay 0đ” hay “confirm QR 0đ” | 0đ đi qua nhánh miễn thu của Order; thêm endpoint xác nhận thu 0đ sẽ làm sai ledger. |

### 4. Tiêu chí hoàn thành

- Customer PICKUP chọn `BANK_TRANSFER`, voucher giảm 100% → tạo **một** đơn CONFIRMED, `NO_PAYMENT/COLLECTED`, không QR.
- Customer dùng điểm giảm 100% hoặc kết hợp voucher + điểm → tính lại chính xác về 0đ, reserve/redeem một lần.
- DELIVERY với phí ship đã nằm trong quote còn 0đ → tạo hợp lệ; nếu phí ship khiến tổng >0 thì vẫn thu phần còn lại, không tự miễn phí.
- POS chọn `CASH` rồi giảm còn 0đ → Order hợp lệ, `CounterOrderModal` không gọi `collectCounter` hoặc báo lỗi thu.
- POS chọn `TRANSFER/BANK_TRANSFER/SPLIT` trước khi giảm về 0đ → chấp nhận Order miễn thu, không tạo QR hay transfer session.
- Web/POS tổng **1đ** → giữ luồng thanh toán dương; không áp dụng nhánh NO_PAYMENT nhầm do làm tròn.
- Client gửi amount giả 0đ trong khi server quote >0 → từ chối hoặc tính đúng số tiền thật; không bypass Payment.
- Giỏ/voucher/điểm thay sau preview khiến tổng trở lại >0 → UI xóa trạng thái miễn thu, server kiểm lại trước create.
- 0đ gọi `payment-session`, `counter-bank-transfer` hoặc `counter-collection` → từ chối/không cần thanh toán, không sinh payment record/receipt.
- Đơn 0đ `order.created` → `paymentMethod` hợp lệ tách `collectionMethod=NO_PAYMENT`; không có `payment.confirmed` giả.
- Đơn 0đ từ READY đến mock pickup/delivered → không trừ ví shipper, không phát sinh COD hoặc trạng thái kẹt.
- Đơn 0đ hoàn tất → report `order_count +1`, doanh thu +0đ; replay event không nhân count.
- Hủy đơn 0đ trước hoàn tất → voucher/điểm hoàn theo policy, không sinh refund ngân hàng hoặc cash 0đ.
- Retry web cùng key/payload sau timeout hoặc reload → một `orderId`; key cũ + payload khác trả `409 RESOURCE_CONFLICT`.
- Retry POS hoặc double click với cùng draft/key → một đơn, không consume voucher/điểm hai lần.
- Thao tác trái store/Customer đọc Order khác → 403/404 theo RBAC, zero amount không mở quyền đọc/đổi trạng thái.
- Email order 0đ ở các bước nhận/chế biến/hoàn tất → ghi “miễn thanh toán”, không nhầm với COD đã thu hoặc SePay thành công.
- Hoàn tất checklist đồng bộ Order, Payment, Shipping, Promotion–Loyalty, Notification, Report, hai UI và Docker/migration; kết quả test nghiệp vụ phải được ghi riêng, không suy từ build healthy.

### 5. Điểm cần chốt

- Chốt tên và ý nghĩa hiệu lực của `paymentMethod` trên zero web/POS; hiện source dùng COD/CASH tương thích, `collectionMethod=NO_PAYMENT` là tín hiệu miễn thu, consumer phải thống nhất.
- Chốt mức giảm được áp vào **phí giao hàng** theo policy hiện có; không coi voucher hàng hóa đương nhiên giảm phí ship.
- Chốt trạng thái Order POS 0đ trước bàn giao và ngưỡng hoàn tất để settle voucher/loyalty; không tự hoàn tất đơn khi miễn thu.
- Chốt hành vi API mở payment-session cho đơn đã miễn thu: response code/DTO ổn định để UI không tạo toast lỗi giả.
- Chốt đối soát với dữ liệu cũ trước migration/event contract và phạm vi thử replay Kafka, shipping mock, receipt/report để chứng minh không mất/nhân dữ liệu.
