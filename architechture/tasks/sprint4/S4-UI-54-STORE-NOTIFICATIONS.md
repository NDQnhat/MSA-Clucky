## UI-54 — Chuông thông báo thực cho Manager và Staff theo chi nhánh

**Phụ trách:** Chưa phân công (Frontend / Manager + Staff React) · **Sprint:** 4 · **Ước lượng:** 3 SP (6 giờ đề xuất) · **Ưu tiên:** 2/5
**Phụ thuộc:** Notification Inbox API và fan-out `order.created`/`inventory.low-stock` theo store, Identity phân quyền Manager/Staff, Session/Gateway.

### 1. User Story

- **Là Manager của chi nhánh**, tôi muốn nhận thông báo đơn mới và cảnh báo tồn kho thấp tại đúng quán đang quản lý để xử lý kịp thời và không bỏ sót thông tin khi đang ở màn khác.
- **Là Staff của chi nhánh**, tôi muốn thấy đơn mới và các thông báo được cấp quyền, mở đúng đơn và đánh dấu đã đọc mà không nhìn thấy tài chính/chi nhánh không thuộc quyền.
- **Là Admin**, tôi muốn màn thông báo toàn hệ thống hiện hữu giữ luồng riêng, trong khi Manager/Staff có inbox theo user/store để không lẫn dữ liệu và quyền.

### 2. Công việc

- Đối chiếu `frontend/react/src/layouts/AdminLayout.tsx`: Admin đang dùng `LiveAdminNotifications`; Manager/Staff dùng `components/ui/AdminNotificationCenter.tsx`. Rà behavior thực tế của component theo cùng layout (chuông, unread, pagination đã có); hoàn thiện đúng những phần còn thiếu, tránh tạo hai inbox Staff.
- Dùng `GET /api/notifications/my` qua Gateway với JWT, không truyền `userId` hoặc `storeId` do trình duyệt tự chọn. Server lấy `userId` và `storeId` tin cậy từ actor; dữ liệu nhận theo người dùng và quán ACTIVE hiện hành, không dựa vào việc ẩn menu.
- Hiển thị badge chưa đọc lấy từ `unreadCount` cùng scope của API; popover có danh sách mới nhất, title, content an toàn, loại `ORDER/INVENTORY/...`, thời gian locale, unread, số trang. Mở rộng filter `unread`/`type` theo contract khi cần; không lấy total trang hiện tại để suy ra tổng chưa đọc.
- Giữ chuông luôn sẵn trên desktop/mobile, có loading, empty và thông báo lỗi rõ. Khi không tải được inbox, giữ trạng thái last-known nhưng phân biệt dữ liệu cũ; không tự suy ra “không có thông báo”.
- Đánh dấu từng thông báo bằng `PUT /api/notifications/{id}/read` và toàn bộ bằng `PUT /api/notifications/read-all`; khóa thao tác trong request, cập nhật/refetch count khi backend xác nhận. Request lặp phải idempotent: không làm unread âm, không chuyển notification của người khác.
- `order.created` quán A phải tới Staff A và Manager A (Admin có inbox riêng); khi bấm xem chi tiết, gọi `GET /api/orders/{id}` để backend kiểm quyền trước khi điều hướng. Không tạo deep link đến đơn mà actor không được phép; nếu order bị hủy/xóa/thay quán thì hiện lỗi an toàn và giữ thông báo.
- `inventory.low-stock` chỉ gửi Manager đúng quán và Admin, theo `episodeId` tránh spam liên tục khi tồn kho vẫn thấp. Click tới màn kho chỉ khi role/route được quyền; Staff không được thấy detail quản lý tồn kho nếu ma trận RBAC không cấp.
- Không hiển thị link refund/payment cho Staff nếu chưa có quyền. Với loại thông báo chưa có action hợp lệ (SYSTEM/PROMOTION/REFUND...), cho đọc và mark read an toàn; chỉ thêm link khi route, target và quyền đã được xác minh.
- Duy trì polling có giới hạn (component hiện có chu kỳ 15 giây), refetch khi focus, tránh yêu cầu đồng thời khi tab ẩn, abort khi unmount/chuyển account; reset inbox/badge của user cũ khi logout hoặc Manager đổi tài khoản/store để không thoáng thấy thông tin quán trước.
- Đồng bộ phân trang/lọc sau một mutation, không bỏ sót thông báo khi số bản ghi đổi. Với hai tab/thiết bị, lần poll tiếp theo đọc `unreadCount` server thay vì tính delta thủ công.
- Bàn giao cho Backend yêu cầu recipient fan-out: consumer xác định Manager/Staff ACTIVE qua Identity/internal contract hoặc projection tin cậy, dùng idempotency theo `eventId + recipient/episode`, ghi gap/retry khi tài khoản quán chưa sẵn. Không join chéo database Notification/Identity.
- Chuẩn hóa error 401 phiên hết hạn, 403 role/scope, 404 notification không thuộc user, 400/429 filter/rate, 5xx mạng; UI không đẩy người dùng sang Admin inbox để “thấy thông báo”. Dịch vi/en, aria-label/badge, keyboard focus, bố cục hẹp theo Clucky brand.
- Nguồn nghiệp vụ: mục 3 `architechture/plans/Zero_Payment_Branch_Notifications_Provisioning_Email_Session_2026-11-15.md`; xác nhận lại hành vi đã tồn tại trước khi bổ sung.

### 3. API

| API / hợp đồng | Quyền / yêu cầu |
| --- | --- |
| `GET /api/notifications/my?page=0&size=20&unread=false&type=ORDER` (đang có) | Actor đăng nhập; với Staff/Manager scope chính xác `userId+storeId` từ server. `size` từ 1 đến 200; `type` hỗ trợ `ORDER, PAYMENT, PROMOTION, SYSTEM, INVENTORY, REFUND` theo service. Response có `content, page, size, totalElements, totalPages, unreadCount`. |
| `PUT /api/notifications/{id}/read` (đang có) | Chỉ người nhận sở hữu notification trong store hiện hành; read lặp trả `isRead=true`; ID người khác trả 404 để tránh lộ. |
| `PUT /api/notifications/read-all` (đang có) | Mark unread của chính actor+store; trả `updatedCount`; không thay Admin/Customer inbox khác. |
| `GET /api/orders/{id}` (đang có) | Staff/Manager đúng store được mở chi tiết theo quyền Order backend; lỗi 403/404 chặn điều hướng sang đơn ngoài scope. |
| Route kho `/admin/inventory` (đang có ở React, không phải API mới) | Chỉ điều hướng khi Manager có quyền kho; xác minh Inventory API/route guard hiện tại, không mở menu Staff bằng UI shortcut. |
| `GET /api/notifications/admin` và `/admin/unread-count` (đang có, luồng riêng) | Chỉ Admin toàn hệ thống; **không** dùng làm nguồn cho chuông Manager/Staff. |
| Fan-out `order.created`, `inventory.low-stock` (event nội bộ, **không phải HTTP**) | Notification backend nhận event store đáng tin cậy, dedup theo event/episode + recipient; quyền gửi/nhận do backend quyết định, UI không phát thông báo. |

### 4. Tiêu chí hoàn thành

- Khách tạo đơn tại cửa hàng A → Staff A và Manager A thấy một thông báo ORDER, Admin nhận theo luồng Admin; B không thấy thông báo A.
- Staff/Manager ở quán B cố đổi query `storeId` hoặc `recipientId` → API vẫn chỉ trả inbox người đang đăng nhập; không lọt tên khách/đơn quán A.
- Tồn kho quán A từ 21 xuống 20 qua ngưỡng thấp → Manager A nhận cảnh báo, Staff A không nhận nếu RBAC chỉ cấp Manager.
- Tồn kho tiếp tục 20 xuống 19 → không spam cảnh báo cùng episode; hồi lên 21 rồi xuống 20 tạo episode mới đúng nguồn backend.
- Event Kafka `order.created` replay hoặc consumer restart → mỗi người nhận chỉ một notification của event; UI không nhân bản dòng/badge.
- Mở chuông với inbox trống → có empty state, unreadCount=0; không báo lỗi và không gọi Admin inbox.
- Đến thông báo khi popover đang mở → polling/refocus cập nhật số chưa đọc và danh sách theo thứ tự thời gian; không mất vị trí lọc do request cũ.
- Đánh dấu một thông báo chưa đọc → server trả thành công, icon/row và unreadCount giảm đúng một; gọi lần hai không giảm thêm.
- Đánh dấu tất cả đã đọc → chỉ notification của tài khoản/store hiện hành bị đổi; trên thiết bị thứ hai count được đồng bộ sau refresh.
- Đánh dấu thất bại do mạng/500 → badge không tự giảm vĩnh viễn; có lỗi và retry/refetch không nhân mutation.
- Chọn lọc unread/type, phân trang lớn hơn 1 → đúng số row, `totalElements` và `unreadCount` toàn scope, không lệch theo trang.
- Bấm ORDER thuộc store được phép → kiểm detail thành công mới điều hướng, giữ context order ID đúng; không đi tới order khác vì stale ref.
- Bấm link ORDER bị thu hồi quyền hoặc ID không tồn tại → API trả 403/404, hiện lỗi an toàn, không lộ thông tin trong URL/preview.
- Staff nhận REFUND/PAYMENT không có quyền thao tác → không hiển thị nội dung/link trái phép; quyền được kiểm ở backend trước khi xem target.
- Đổi từ Staff quán A sang Manager quán B/log out rồi log in → không nháy badge/nội dung A; request cũ bị hủy, data mới theo B.
- Phiên bị khóa hoặc hết hạn → chuyển đăng nhập theo policy; lỗi 403 nghiệp vụ không được biến thành quyền Admin hoặc đăng xuất vô cớ.
- Thử click notification ID của user khác bằng HTTP trực tiếp → 404/403 phù hợp, không thay `isRead` của người kia.
- Mobile, keyboard, vi/en: chuông có nhãn đọc được, popover không vượt màn, đọc thời gian đúng locale và thông báo lỗi không che nút.
- Handoff nguồn event/API, ma trận role→type→route và bằng chứng nghiệm thu hai quán/hai role/hai thiết bị; ghi rõ fan-out backend nào còn chờ.

### 5. Điểm cần chốt

- Chốt ma trận từng event `ORDER/INVENTORY/PAYMENT/REFUND/PROMOTION/SYSTEM` đến Staff, Manager, Admin theo role/store; đặc biệt refund và số tiền hiển thị được hay không.
- Xác nhận mỗi chi nhánh dùng đúng một Manager + Staff shared account hay nhiều nhân sự; Notification backend phải fan-out theo Identity hiện hành khi account khóa/đổi.
- Chốt `episodeId` và cửa sổ chống spam tồn kho thấp, quy tắc gửi khi recipient chưa provision/đang locked và cách retry không đẩy nhầm quán.
- Chốt đích điều hướng cho từng loại thông báo và điều kiện route được phép, gồm order chuyển store/hủy và link kho/refund trên từng role.
- Chốt cadence polling/thời gian lưu/badge nhiều thiết bị; ưu tiên dữ liệu server, không tự bật SSE/WebSocket khi hạ tầng chưa xác nhận.
