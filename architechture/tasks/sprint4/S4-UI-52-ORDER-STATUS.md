## UI-52 (Order Status) — Chuẩn hóa nhãn và thao tác DINE_IN / TAKE_AWAY / DELIVERY tại Staff

**Phụ trách:** Chưa phân công (Frontend / Staff React) · **Sprint:** 4 · **Ước lượng:** 5 SP (8 giờ đề xuất) · **Ưu tiên:** 2/5
**Phụ thuộc:** Order/Shipping contract, UI-53 mô phỏng giao hàng, trạng thái thanh toán BE-87/BE-88 khi áp dụng đơn tại quầy và 0đ.

### 1. User Story

- **Là Staff**, tôi muốn nhìn rõ khách ăn tại quán, mang đi hay được giao tận nơi trên mọi màn vận hành để chuẩn bị, bàn giao và xác nhận đơn theo đúng cách nhận.
- **Là Store Manager**, tôi muốn theo dõi đơn giao thất bại, giao lại hoặc đóng xử lý mà không bị ghi nhầm là hoàn tất thành công.
- **Là Staff được phân quán**, tôi muốn xem thông tin người nhận của đơn DELIVERY thuộc chi nhánh mình để liên hệ đúng người, với dữ liệu và quyền do backend xác thực.

### 2. Công việc

- Đối chiếu `frontend/react/src/pages/Staff/StaffOrdersPage.tsx`, `OrderCard.tsx`, `OrderDetailDrawer.tsx`, `ReceiptBillModal.tsx`, POS, Admin Orders và `frontend/react/src/lib/api.ts`; lập ma trận nhãn + action cho card, filter, drawer, receipt/KOT và các vị trí cùng hiển thị đơn.
- Chuẩn hóa **fulfillment** từ dữ liệu Order thực tế: `orderType=DELIVERY` luôn thành **Giao hàng**; `PICKUP + diningOption=DINE_IN` thành **Ăn tại quán**; `PICKUP + TAKE_AWAY` thành **Mang về**. Không dùng `paymentMethod`, nguồn WEB/COUNTER hoặc `diningOption=TAKE_AWAY` của DELIVERY để đổi nhãn.
- Với Order cũ thiếu `diningOption`: WEB/PICKUP mặc định TAKE_AWAY **chỉ khi contract checkout xác nhận**; COUNTER/PICKUP thiếu dữ liệu phải hiện “Nhận tại quán/Chưa rõ” hoặc xử lý theo policy, không tự coi là DINE_IN. Tận dụng một helper/DTO nhất quán để tránh card đúng mà bill sai.
- Chuẩn hóa nhãn và hành động từ backend: `CONFIRMED → PREPARING → READY`. Ở READY, DINE_IN xác nhận “Khách đã nhận món tại quầy”, TAKE_AWAY xác nhận “Khách đã nhận món”; không thêm số bàn, phục vụ tận bàn hoặc thao tác giao hàng cho hai mode này.
- Với DELIVERY: READY = “Chờ shipper mô phỏng đến nhận”; SHIPPING = “Đang giao”; khi shipment FAILED = “Giao thất bại/cần xử lý”, khi đóng ca xử lý = “Không giao được”, khi DELIVERED và Order COMPLETED = “Giao thành công (mô phỏng)”. Nút complete trực tiếp từ READY/SHIPPING phải bị loại ở UI và bị Order backend từ chối; không dùng timer để kết luận hoàn tất.
- Đọc Shipment từ Shipping Service qua Gateway, nhận `status`, `phase`, `resolution`, `attemptNumber`, `serverNow` và dữ liệu lý do thất bại. Kết nối cùng UI-53; khi action ship đã thay đổi, refresh Order + Shipment để tránh chip giao thất bại bị trôi khỏi hàng đợi.
- Card và drawer DELIVERY đọc snapshot `receiverName/receiverPhone/deliveryAddress`, notes, phí nếu tồn tại từ Order/Shipment DTO; hiển thị SĐT có hành động gọi cho Staff đúng quyền. Phân biệt người đặt với người nhận; thiếu trường thì báo thiếu để xử lý, không tự lấy từ profile hiện tại hoặc tự tạo địa chỉ.
- Chỉnh filter/số đếm riêng cho `READY`, `SHIPPING`, `NEEDS_RESOLUTION`, `DELIVERY_FAILED` theo contract; giữ đơn đang lỗi trong danh sách có thể xử lý, không tự rơi vào tab “Hoàn thành” do frontend suy luận.
- Đồng bộ Payment độc lập với Order/Shipment: đơn `collectionMethod=NO_PAYMENT` và `totalAmount=0` ghi “Miễn thanh toán — 0đ”; COD mock không ghi “Đã thu tiền khách” chỉ vì shipper lấy hàng; QR chỉ hiện đã trả khi Payment xác nhận.
- Xử lý pending action, double click, 401/403/404/409, refetch khi focus/tab/route reload, polling hợp lý, stale response giữa các lần lọc; copy vi/en, responsive và khả năng đọc nhãn bằng keyboard/screen reader.
- Chỉ gửi API mutation qua Gateway; backend giữ ownership `storeId`, state transition và payment gate. UI không truyền quyền theo role toggle hoặc tự cập nhật persisted status khi request thất bại.
- **Phân biệt định danh:** đây là **UI-52 thứ hai trong Sprint 4**, khác `S4-UI-52-SMS-OTP.md`; không dùng ID trần “UI-52” khi đồng bộ Lark.

### 3. API

| API / hợp đồng | Quyền / yêu cầu |
| --- | --- |
| `GET /api/orders?storeId=&status=&page=&size=` (đang có) | Staff/Manager chỉ đơn chi nhánh được gán, Admin theo RBAC; backend phải giới hạn store kể cả khi client sửa query. Payload có `orderType`, `diningOption`, `status`. |
| `GET /api/orders/{id}` (đang có) | Customer chủ đơn hoặc actor vận hành đúng store; nhận snapshot người nhận, các trường tiền, ghi chú hợp lệ; không lấy dữ liệu riêng của quán khác. |
| `PUT /api/orders/{id}/status` (đang có) | Staff/Manager/Admin đúng store theo state machine; `{status}` theo DTO hiện hành; DELIVERY hoàn tất chỉ do backend hợp lệ sau Shipment.DELIVERED, không thể cưỡng bức bằng UI. |
| `GET /api/shipping/shipments/{orderId}/tracking` (đang có) | Customer chủ đơn; Staff/Manager/Admin đúng store; Shipment là nguồn chuẩn `status/phase/resolution/attempt/serverNow` cho nhãn giao. |
| `POST /api/shipping/shipments/{orderId}/simulation/{fail,return,retry,close}` (đang có) | Fail/return: Staff/Manager/Admin; retry/close: Manager/Admin; giới hạn store tại backend, `attemptNumber/version` và Idempotency-Key theo contract. Chi tiết action ở UI-53. |
| `GET /api/orders/my` (đang có; nếu liên kết từ Customer) | Customer chỉ đơn của mình; dùng để so sánh nhãn, không cấp quyền đọc Staff từ trang khách. |
| Hợp đồng tổng hợp `fulfillmentMode` (đề xuất, **chưa** là endpoint) | Nếu cần tái sử dụng nhiều màn, thống nhất helper/DTO từ `type + diningOption` trước; không tạo trạng thái persisted thứ hai không có nguồn chân lý. |

### 4. Tiêu chí hoàn thành

- Đơn COUNTER/PICKUP/DINE_IN ở card, drawer, bill/KOT → đồng nhất “Ăn tại quán”; trạng thái READY mời khách tự ra quầy, không yêu cầu bàn.
- Đơn COUNTER/PICKUP/TAKE_AWAY ở các màn → đồng nhất “Mang về”; READY có action xác nhận khách đã nhận tại quầy.
- Đơn WEB/DELIVERY có `diningOption=TAKE_AWAY` lịch sử → vẫn hiện “Giao hàng” ở card/detail/receipt; không bị đổi sang mang về.
- Order WEB/PICKUP thiếu diningOption và Order COUNTER/PICKUP cũ thiếu dữ liệu → thực hiện hai chính sách hiển thị đã chốt riêng, không tự suy diễn DINE_IN.
- DINE_IN/TAKE_AWAY đang PREPARING mà bấm complete trực tiếp → backend từ chối trạng thái không hợp lệ, UI refetch không giả thành công.
- DINE_IN/TAKE_AWAY READY và payment gate hợp lệ → xác nhận đã nhận một lần thành COMPLETED; double click/timeout không tạo hai kết quả hoàn tất.
- DELIVERY READY khi Shipment.CREATED/ASSIGNED → hiện đang chờ 5 giây theo dữ liệu server, không có nút Staff complete.
- DELIVERY SHIPPING → hiển thị đang giao và mốc chuyến hiện tại; F5/đổi tab không đổi ngược trạng thái hoặc đếm từ đầu.
- DELIVERY FAILED trong quá trình vận chuyển → chuyển sang nhóm cần xử lý; không biến thành COMPLETED/CANCELLED hoặc biến mất khỏi hàng đợi.
- Manager retry tạo attempt mới → danh sách/card/chi tiết hiển thị số lần mới từ backend; refresh không lặp attempt.
- Manager close thất bại → hiện “Không giao được” theo Order/Shipment contract, không tính là hoàn tất thành công.
- Shipment DELIVERED nhưng Order chưa commit COMPLETED → UI hiển thị “Đang xác nhận” dựa vào hai trạng thái, không tuyên bố order đã hoàn thành trước server.
- DELIVERY có receiver snapshot đầy đủ → Staff đúng store xem được tên, số gọi, địa chỉ, ghi chú; chi tiết hóa đơn không lộ dữ liệu quá phạm vi cần in.
- Đơn cũ thiếu địa chỉ hoặc receiverPhone → hiển thị nhãn thiếu thông tin và hướng xử lý; không tạo số điện thoại/địa chỉ giả.
- Staff/Manager quán B thay `storeId` hoặc gọi GET/mutation ID đơn quán A → backend trả 403/404, UI không hiển thị snapshot nhạy cảm.
- COD mock đang giao → không hiện “Đã thu tiền khách”; đơn QR chưa được Payment xác nhận → không hiển thị “Đã thanh toán”.
- Đơn cuối cùng 0đ do voucher/điểm → nhãn “Miễn thanh toán — 0đ”; card không đòi mã QR hoặc cash dù mode là DELIVERY.
- Session hết hạn, request 409 vì thay đổi đồng thời, timeout khi cập nhật → xử lý đúng lỗi, giữ trạng thái server sau tải lại và không commit local sai.
- Mobile/desktop và vi/en: badge, action, filter counts, receipt/KOT thể hiện cùng thuật ngữ; bàn giao bảng ánh xạ mode/state, ảnh màn và kết quả nghiệm thu.

### 5. Điểm cần chốt

- Với `PICKUP` cũ thiếu `diningOption`, chốt phân biệt WEB/COUNTER và có cần migration/sửa dữ liệu hay giữ “Nhận tại quán/Chưa rõ”.
- Xác nhận trạng thái Order khi Shipping đã FAILED nhưng chưa đóng, sau retry và sau đóng thất bại (`DELIVERY_FAILED` hoặc projection); nhãn/filter phải phản ánh state machine thực sự.
- Chốt thời điểm được hoàn tất DINE_IN/TAKE_AWAY theo điều kiện Payment/0đ; không bật đường complete sớm do giới hạn UI.
- Chốt trường người nhận/địa chỉ/ghi chú/phí ship thật của DTO cho đơn cũ và mức độ được in ra KOT/bill theo quyền riêng tư.
- Chốt trách nhiệm ai phát chuyển `SHIPPING/COMPLETED` cho DELIVERY và cơ chế báo 409 khi đồng thời; Staff không phải actor xác nhận “shipper đã giao”.
