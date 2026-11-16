## BE-92 — Áp mã voucher cá nhân ở đặt hàng nhanh bên Staff, kiểm tra scope và số lượt toàn campaign

**Phụ trách:** Theo PIC hiện có trên Lark · **Sprint:** 4 · **Ước lượng:** 5 SP · **Time Estimate:** 8 giờ · **Priority đề xuất:** 1
**Phụ thuộc:** BE-34 (campaign voucher), BE-87 (POS/Payment), BE-88 (đơn 0đ), BE-93 (QR voucher), contract Order–Promotion.

### 1. User Story

- **Là Staff**, tôi muốn nhập mã cá nhân hoặc quét QR voucher khách đưa để giảm giá đơn nhanh tại quầy mà không cần hỏi OTP, email hay đăng nhập tài khoản chủ voucher.
- **Là Customer**, tôi muốn một voucher chỉ bị dùng một lần khi đơn thực sự hoàn tất và được trả lại nếu đơn bị hủy hợp lệ, để không mất quyền lợi vì lỗi thanh toán hoặc thao tác nhầm.
- **Là Admin/Manager**, tôi muốn kiểm soát quán được áp dụng, điều kiện đơn và quota toàn campaign trên server, không cho mã dùng vượt số lượt hoặc sai phạm vi.

### 2. Công việc

- Rà `CreateCounterOrderRequest`, `OrderService.previewCounterOrder/createCounterOrder`, `PromotionClient`, `VoucherController`, `PersonalVoucherService`, và event settlement; thống nhất `voucherCode`/`voucherInstanceId` và snapshot discount giữa preview, create và receipt.
- POS cho nhập mã voucher cá nhân hoặc quét QR dạng `CLUCKY:V1:...`; mã đúng là bearer credential, **không bắt OTP/không kiểm customerId của chủ voucher** trong luồng Staff. Phân biệt rõ mã voucher với QR Loyalty động.
- Normalize mã theo chuẩn parser, không cắt ngắn gây đụng code, không ghi nguyên mã, salt, verifier hoặc ciphertext trong log, URL, audit hay thông báo lỗi. Giới hạn số lần thử mã/Staff/store/IP.
- Backend validate campaign ACTIVE, đúng thời gian, đúng store trong multi-store/ALL, giá trị tối thiểu, discount type/cap, quota còn, trạng thái instance, order items và giới hạn mỗi đơn một voucher; không dựa vào tổng tiền client tự khai.
- Campaign cá nhân **mỗi account một instance/campaign** và **mỗi instance chỉ một lần redeem thành công**; không có `instance.usage/maxUsage` > 1. `usage/maxUsage` trên UI là số lần của **toàn campaign**, khi reserve tính thêm `reservedCount` để không oversubscribe.
- Preview không tạo hold, chỉ tính `voucherDiscount` hợp lệ. Khi thay giỏ, store, payment hoặc điểm, Order phải re-quote; xử lý voucher khiến tổng thành 0đ theo BE-88, không sinh VietQR/payment session 0 VND.
- Khi Order tạo thành công, reserve instance và quota gắn `orderId`, idempotency key; lock campaign/instance và unique reservation theo order để hai Staff không tiêu cùng một mã. Transaction lỗi phải trả kết quả rõ, không để đơn thành công giả với voucher chưa reserve.
- Khi Order `COMPLETED` và thanh toán `PAID/COLLECTED` thật, settle sang `REDEEMED`, tăng usage đúng một lần. Cancel hợp lệ giải phóng hold/quota, kể cả retry; refund sau Completed giữ voucher `REDEEMED` và **không hoàn quota**.
- Nếu nghiệp vụ cho phép hủy thực sự sau settle, ghi reversal riêng có audit/cancelId, bảo toàn lịch sử; không coi một yêu cầu refund là cancellation để lấy lại voucher.
- Giữ đường xử lý đơn/voucher LEGACY còn đang hoạt động theo chính sách đã có, tránh tự di chuyển sang PERSONAL hoặc sửa migration cũ; khi cần migration mới chỉ ở schema sở hữu.
- Staff/Manager chỉ tạo đơn/quét tại store được phân quyền; Admin/Manager quyền cấu hình campaign/proposal theo RBAC riêng. Web Customer chỉ được dùng instance thuộc JWT của mình; không mở ô nhập mã voucher trên Web vì POS có nhập mã.
- Khi Promotion timeout hoặc các consumer nhận event trễ, có reconcile reservations/quota bằng Order authoritative; retry không nhân discount, giữ trạng thái minh bạch trên POS.

### 3. API

| API / sự kiện | Quyền / yêu cầu |
| --- | --- |
| `POST /api/promotions/vouchers/validate` | Xác thực mã/instance theo DTO; chỉ preview, không consume hoặc giữ quota; cần kiểm actor/store ở luồng sử dụng |
| `POST /api/orders/counter/preview` | STAFF/STORE_MANAGER/ADMIN đúng store; nhận voucherCode và order draft, tính discount dựa vào server |
| `POST /api/orders/counter` | Staff đúng store; tạo order `Idempotency-Key`, reserve voucher theo snapshot quote, không tin giảm giá client |
| `POST /internal/loyalty/voucher-reservations` | Order/internal trust; giữ quota/instance với orderId, storeId, amount và discountAmount có kiểm tra |
| `POST /internal/loyalty/voucher-reservations/{orderId}/release` | Order/internal trust; trả hold khi cancel/fail hợp lệ, idempotent |
| `POST /internal/loyalty/voucher-reservations/{orderId}/refund` | Order/internal trust; ghi refundId, **không trả mã hoặc quota** sau Completed |
| `GET /api/promotions/vouchers/mine`, `GET /api/promotions/vouchers/my/{instanceId}` | CUSTOMER owner; xem instance/mã cá nhân và QR của chính mình |
| `POST /api/promotions/vouchers/{campaignId}/claim` | CUSTOMER; phát đúng một mã cá nhân cho campaign/account, retry không phát thêm |
| `POST/PUT /api/promotions/vouchers`, `POST /api/promotions/vouchers/store-proposals` | ADMIN quản lý campaign; STORE_MANAGER gửi proposal đúng quán và chờ phê duyệt |
| `order.completed`, `order.cancelled`, refund events | Promotion settle/release/reverse và audit theo eventId/orderId/refundId, không làm tăng quota/lượt hai lần |

- Các endpoint trên đang có trong controller, trừ khi event contract từng môi trường chưa chốt. Kế hoạch từng đề xuất `/api/orders/counter/vouchers/preview`, nhưng nguồn triển khai hiện dùng `/api/orders/counter/preview`; ưu tiên endpoint đã có để tránh nhân bản logic pricing.

### 4. Tiêu chí hoàn thành

- Staff nhận mã voucher của Customer A qua giấy/điện thoại, nhập vào POS: Giảm giá hợp lệ dù A không đăng nhập tại quầy, không OTP/phone owner confirmation.
- Quét QR voucher cá nhân của A: Giảm tương đương nhập tay; quét QR Loyalty vào vùng Voucher phải báo sai loại và không áp dụng.
- Campaign áp quán A/C, không áp B: Cùng mã dùng hợp lệ tại A/C nếu còn lượt; B preview/create bị backend từ chối, không thay reservation.
- Campaign `maxUsage=100`, `usage=99`, `reservedCount=1`: Không nhận reserve mới; progress vẫn thể hiện 99/100 lần đã dùng toàn hệ thống, không hiển thị 1/5 của một khách.
- Customer A/B mỗi người nhận một voucher cùng campaign: Mã A và B khác nhau, mỗi người một instance; khi A hoàn tất, campaign usage tăng một cho cả hai màn.
- Voucher chưa bắt đầu, hết hạn, bị vô hiệu, mã sai hoặc đã redeem: Trả mã lỗi đúng, không làm lộ account chủ mã và không giữ quota.
- Đơn 80.000đ với voucher yêu cầu tối thiểu 100.000đ: Không giảm; thêm sản phẩm đưa giá đủ ngưỡng và re-quote thì nhận đúng discount.
- Một voucher có max discount nhỏ hơn phần trăm tính được: Số giảm bằng cap, tổng không âm, receipt và preview khớp với Order persisted.
- Staff nhập voucher và Loyalty chỉ bằng SĐT: Có quyền giảm voucher và earn-only, tuyệt đối không tự dùng điểm của khách.
- Staff vừa áp voucher vừa quét QR Loyalty: Hai quyền hoạt động độc lập; trừ theo thứ tự pricing đã chốt, không double-discount hoặc earn trên phần điểm đã sử dụng.
- Voucher giảm hết 100% bill: Tạo đơn 0đ không cần QR ngân hàng, Payment không tạo giao dịch giả; nếu bill thành 1đ thì xử lý tiền dương bình thường.
- Hai Staff dùng cùng mã trên hai POS: Chỉ một reservation hợp lệ, kẻ còn lại nhận conflict; quota không âm ngay cả khi retry.
- Retry create Order sau timeout bằng cùng idempotency key: Trả cùng order/voucher reservation; payload khác với cùng key bị từ chối.
- Order hủy trước Completed: Release quota một lần, instance trở về trạng thái dùng được nếu còn hạn; event hủy lặp không hoàn quota nhiều lần.
- Order Completed sau đó refund toàn bộ/một phần: Không cấp lại instance; campaign usage không giảm; refundId lặp không nhân audit.
- Nếu Order cho phép hủy hợp lệ sau settlement: Reversal lịch sử bất biến, trả quota một lần theo cancelId; nếu state chưa hỗ trợ, không mở route hủy trái workflow.
- Customer Web sử dụng `voucherInstanceId` không thuộc actor: Backend chặn dù biết UUID; POS chỉ cần bearer code đúng policy, không dùng owner check sai ngữ cảnh.
- Store Manager quán B cố quản lý campaign của A hoặc Staff tự tạo campaign: Bị chặn bởi RBAC, không được nới quyền để test luồng bán.
- Promotion timeout sau Order preview: POS hiển thị lỗi/cho retry an toàn, không báo đã sử dụng voucher khi chưa commit; reconciliation không để orphan hold kéo dài.
- Bàn giao contract, error catalog, state transitions, migration nếu có, kết quả test order/usage/refund và commit/evidence; nêu phần chưa xác minh.

### 5. Điểm cần chốt

- PO xác nhận thứ tự trừ voucher, tier và points, điều kiện giá tối thiểu trước/sau giảm và khả năng chồng khuyến mãi; chỉ dùng chính sách đã phê duyệt.
- Chốt cách xử lý voucher campaign legacy còn chạy và các order đang reserved khi deploy/rollback, không viết lại lịch sử usage cũ.
- Hủy Completed có thật sự tạo state `CANCELLED` hay chỉ refund; reversal voucher chỉ áp dụng cho cancellation authoritative, không từ nhãn UI.
- Xác nhận response mã lỗi và kiểm rate limit cho voucher bearer code, cách che mã trong receipt/audit, khả năng hiển thị mã trên Customer Web.
- Kiểm cần thiết của endpoint preview riêng cho voucher; ưu tiên `POST /api/orders/counter/preview` hiện có cho một nguồn giá.
