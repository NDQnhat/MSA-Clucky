## UI-53 (Mock Shipping) — Theo dõi shipper mô phỏng và xử lý thất bại trên Customer/Staff

**Phụ trách:** Chưa phân công (Frontend / Staff React + Customer Next.js) · **Sprint:** 4 · **Ước lượng:** 5 SP (10 giờ đề xuất) · **Ưu tiên:** 3/5
**Phụ thuộc:** Shipping mock engine/Tracking API, Order fulfillment UI-52, Payment/Order 0đ (BE-88), dữ liệu route Mapbox hợp lệ.

### 1. User Story

- **Là Customer có đơn DELIVERY**, tôi muốn xem quán đã chuẩn bị xong, shipper **mô phỏng** đến lấy hàng, đang di chuyển trên tuyến đường và kết quả giao để theo dõi đơn mà không tưởng là có tài xế đối tác thật.
- **Là Staff của quán xử lý đơn**, tôi muốn xem cùng tiến độ với Customer và báo giao thất bại khi có sự cố trong lúc di chuyển, rồi xác nhận hoàn hàng.
- **Là Manager**, tôi muốn quyết định giao lại hoặc đóng trường hợp thất bại có ghi nhận lý do, đúng phạm vi quán và không tạo đơn giao/thu tiền trùng.

### 2. Công việc

- Rà và dùng chung contract `GET /api/shipping/shipments/{orderId}/tracking` qua Gateway cho Customer và Staff; tích hợp trong trang chi tiết đơn `frontend/nextjs` và `frontend/react/src/components/ShipmentSimulationPanel.tsx`. UI lấy `status`, `phase`, `resolution`, `attemptNumber`, `version`, `serverNow`, route và timestamp **từ Shipping**, không duy trì một shipment demo trên trình duyệt.
- Khi đơn DELIVERY sang Order.READY và Shipping kích hoạt attempt hợp lệ, hiển thị **5 giây chờ tài xế đến nhận** theo `riderArrivalDueAt`; sau khi backend xác nhận pickup, hiển thị **20 giây di chuyển** từ `deliveryStartedAt` đến `deliveryDueAt`. Mốc có thể trễ do job/điều kiện thanh toán; đếm không được khởi tạo lại từ lúc mở trang.
- Hiển thị đầy đủ pha Shipment `CREATED/ASSIGNED/PICKED_UP/SHIPPING/DELIVERED/FAILED` và các pha phụ như `WAITING_RIDER/ARRIVED_PENDING_PROOF/RETURNED/CLOSED_UNDELIVERABLE` nếu contract trả. Phân biệt Order READY với Shipment CREATED, Shipment DELIVERED với Order COMPLETED.
- Đọc `routeGeometry` GeoJSON/LineString do Shipping lưu theo attempt, vẽ Mapbox polyline và nội suy marker **theo chiều dài tích lũy của tuyến**. Đồng bộ với `serverNow`, hiệu chỉnh lệch đồng hồ mỗi lần poll; animation chỉ là hiển thị, không được gửi mutation xác nhận giao khi marker tới đích.
- Hai ứng dụng dùng cùng snapshot route/timestamp; khi reload, tab background, reconnect hoặc xem từ máy thứ hai sẽ tính lại vị trí trên attempt đã persist, không reset thành chuyến mới. Poll vừa phải và refresh khi tab focus; chống response cũ ghi đè attempt mới.
- Staff đúng store có nút **Giả lập giao thất bại** chỉ lúc `status=SHIPPING` và còn trước `deliveryDueAt`. Modal yêu cầu `reasonCode`, `OTHER` cần `note`; gửi `attemptNumber`, `version` và khóa idempotency. Khóa bấm khi pending; 409 race với auto-deliver phải refetch trạng thái server.
- Sau `FAILED`, đóng băng marker tại `failedAt`, hiển thị lý do và hướng xử lý; Staff/Manager đúng store xác nhận hoàn hàng, chỉ Manager/Admin được tạo lần giao lại hoặc đóng thất bại. Không mở nút retry trước khi đáp ứng điều kiện hoàn hàng và limit attempt backend.
- Khi retry, hiển thị `attemptNumber` mới, full timeline **5s + 20s**, route mới nếu địa chỉ được thay đổi hợp lệ; vẫn một Shipment theo order, giữ lịch sử attempt và proof trước đó theo backend.
- Customer chỉ xem route, thời gian/ETA, tình trạng, thông tin thanh toán ở mức phù hợp và ảnh **MINH CHỨNG MÔ PHỎNG** khi API có; không có nút fail/return/retry/close hoặc nội dung ví shipper. Staff chỉ thấy thông tin người nhận quán mình; không render ảnh minh chứng như ảnh thực địa.
- Với COD, nói rõ “Khách thanh toán cho shipper ngoài hệ thống”; khi pickup có thể hiển thị “Quán được ứng tiền (mô phỏng)” **theo projection backend** nhưng không khẳng định đã thu tiền khách. Với QR trả trước, không debit ví; với đơn `NO_PAYMENT/0đ`, hiện “Miễn thanh toán” và không ghi nợ/ứng ví, refund hoặc payment giả.
- Nếu thiếu tọa độ, route, token Mapbox công khai `pk.` hoặc Mapbox browser unsupported, giữ status Shipment thực và thông báo lỗi/cho tải lại; không vẽ đường chim bay, không tự thành công hoặc tự tạo route ở client. Feature flag Shipping hiện có `clucky.shipping.mock.enabled` do server quyết định; bản build không được biến simulation thành shipping production.
- Duy trì loading/empty/error/retry, diễn giải accessible khi chỉ nhìn map, palette và vi/en; hạn chế poll khi tab ẩn, hủy request khi unmount; giao diện phải đọc được trên điện thoại và desktop.
- Phạm vi UI-53 Sprint 4 là **shipping mock**; `architechture/tasks/UI-53.md` đang là **quản lý Customer Admin của Sprint 3**, tuyệt đối không sửa/ghi đè. Nguồn nghiệp vụ: `architechture/plans/Order_Fulfillment_Shipping_Mock_Replan_2026-11-13.md`, có đối chiếu với source đã triển khai.

### 3. API

| API / hợp đồng | Quyền / yêu cầu |
| --- | --- |
| `GET /api/shipping/shipments/{orderId}/tracking` (đang có) | CUSTOMER chủ đơn; STAFF/STORE_MANAGER/ADMIN đúng store. Trả `serverNow, status, phase, routeGeometry, deliveryStartedAt, deliveryDueAt, attemptNumber, version, proof` theo quyền. |
| `POST /api/shipping/shipments/{orderId}/simulation/fail` (đang có) | STAFF/STORE_MANAGER/ADMIN đúng store; `{reasonCode,note,attemptNumber,version,idempotencyKey?}`; trong cửa sổ SHIPPING trước deadline server, race trả 409. |
| `POST /api/shipping/shipments/{orderId}/simulation/return` (đang có) | STAFF/STORE_MANAGER/ADMIN đúng store; body `{note,attemptNumber,version,idempotencyKey?}`; ghi nhận hoàn hàng và bù ví COD demo một lần theo backend. |
| `POST /api/shipping/shipments/{orderId}/simulation/retry` (đang có) | STORE_MANAGER/ADMIN đúng store; body `{note,attemptNumber,version,idempotencyKey?}`; tạo attempt mới khi backend xác nhận đủ điều kiện. |
| `POST /api/shipping/shipments/{orderId}/simulation/close` (đang có) | STORE_MANAGER/ADMIN đúng store; body tương tự, `note` cần rõ lý do; không tự gọi refund thật. |
| `GET /api/orders/{id}` hoặc `GET /api/orders/my` (đang có) | Staff/Manager đúng store hoặc Customer chủ đơn; dùng ghép Order với Shipment, không nâng quyền truy vấn của Customer. |
| `Idempotency-Key` header (đang được Controller chấp nhận) | Mutation có thể mang khóa qua header hoặc body; Client phải giữ khóa ổn định khi retry cùng ý định và làm mới theo attempt/action mới. |
| Hợp đồng ảnh/đổi địa chỉ retry (cần chốt, **không giả là API có**) | Proof do Shipping sinh với nhãn simulated; yêu cầu đổi địa chỉ/phí phải theo nghiệp vụ riêng đã duyệt, không tự thêm endpoint map/editor. |

### 4. Tiêu chí hoàn thành

- Đơn PICKUP/DINE_IN/TAKE_AWAY → không hiện bản đồ shipper hoặc điều khiển mô phỏng; chỉ DELIVERY có shipment mới tải tracking.
- DELIVERY chuyển READY nhưng Shipping chưa có route/attempt → hiển thị đang chờ dữ liệu hợp lệ, không khởi đồng hồ 5 giây trên client.
- Shipping ASSIGNED có `riderArrivalDueAt` → hai màn cùng đếm thời gian server đến pickup; F5 sau 3 giây không làm lại 5 giây.
- COD được Shipping xác nhận pickup sau 5 giây → UI sang SHIPPING nhưng không hiển thị “đã thu tiền COD từ khách”.
- QR `PAID` hoặc đơn miễn thanh toán 0đ → di chuyển bình thường mà không xuất hiện yêu cầu ứng tiền/thu hộ thật.
- Hai thiết bị mở cùng `orderId/attemptNumber` → nhận cùng route, trạng thái và vị trí nội suy tương ứng mốc server; không chạy hai bộ timer độc lập.
- Trong 20 giây, map đi theo polyline có nhiều đoạn với tốc độ theo chiều dài; đến cuối giữ marker tại đích, không tự báo DELIVERED nếu proof/backend chưa xác nhận.
- Staff bấm fail ở giây thứ 3 hoặc 19, có lý do hợp lệ → Shipping trả FAILED, marker dừng tại `failedAt`; mở lại vẫn đứng ở cùng vị trí.
- Staff bấm fail lúc backend vừa hoàn tất → nhận 409/trạng thái mới, reload; không có attempt vừa DELIVERED vừa FAILED.
- Chọn OTHER không nhập note → nút xác nhận khóa và backend từ chối nếu cố gửi payload thiếu; không phát mutation rỗng.
- Double click hoặc timeout rồi retry fail/return cùng idempotency key → chỉ có một chuyển trạng thái/hoàn tiền ví ảo; UI đọc lại kết quả sau response mơ hồ.
- Staff quán A xác nhận hoàn hàng → chỉ ảnh hưởng shipment thuộc A; Staff không có nút retry/close và gọi API trực tiếp cũng bị 403.
- Manager retry khi hàng chưa return hoặc vượt `maxAttempts` → API từ chối, UI hiển thị lý do, không nhân shipment/attempt bất hợp lệ.
- Manager retry hợp lệ → attempt mới chạy lại đủ 5 + 20 giây, số lần và timeline được đồng bộ qua reload, không dùng proof cũ.
- Manager đóng giao thất bại → Shipment giữ FAILED/resolution CLOSED và Order không phát complete/loyalty thành công; Customer thấy trạng thái hỗ trợ phù hợp.
- Không có route, mất mạng Mapbox, token thiếu/không phải `pk.` → thông báo và nút thử lại khi phù hợp; không hiển thị marker giả, không đổi state backend.
- Proof tạo chậm/lỗi → hiện “Đã đến nơi — đang xác nhận giao”; chỉ DELIVERED có minh chứng server thật dưới nhãn mô phỏng.
- Customer đoán ID quán khác hoặc sửa request action bằng devtools → backend 403/404; không thấy SĐT, địa chỉ, ví demo hoặc nút điều phối.
- Bộ chứng cứ nghiệm thu có màn Customer/Staff, kịch bản fail/retry/close, đồng bộ hai máy, vi/en và ghi rõ demo flag/Mapbox được cấu hình thế nào.

### 5. Điểm cần chốt

- Chốt hợp đồng thời gian/route chuẩn theo engine đang chạy (`clucky.shipping.mock.enabled`, `attemptNumber/version`, `serverNow`); tài liệu kế hoạch dùng một số tên dự kiến khác, UI theo DTO/API thực tế.
- Chốt lý do giao thất bại public cho Customer, quy tắc `OTHER`, cần che địa chỉ/SĐT và nội dung proof thế nào trên từng role.
- Chốt `maxAttempts`, điều kiện return trước retry/close, tình huống hàng hỏng/chưa hoàn và quyền Manager/Admin ở từng chi nhánh.
- Chốt quy trình thay đổi địa chỉ/route khi retry, kiểm lại phạm vi giao và phí; UI chỉ hiển thị trạng thái khi chưa có contract sửa địa chỉ.
- Chốt cách biểu diễn COD ứng quán (mô phỏng), miễn thanh toán 0đ, refund thật và nghĩa của “giao thành công” trong báo cáo/điểm; không trình bày mock wallet như tiền thu thực.
