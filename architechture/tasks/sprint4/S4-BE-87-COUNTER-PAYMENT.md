## BE-87 — Thu tiền tại quầy cho đơn Staff/POS, đồng bộ Order, Payment và đối soát

**Phụ trách:** Chưa phân công · **Sprint:** 4 · **Ước lượng đề xuất:** 8 SP (12 giờ)
**Phụ thuộc:** POS quote/create và bảo vệ `Idempotency-Key`; C1–C4 bank/collection; SePay feed; S4-BE-88 cho tổng tiền 0đ.
**Ưu tiên đề xuất:** 1/5 · **Phạm vi:** Backend / Cart-Order + Payment và contract tích hợp POS.

### 1. User Story

- **Là Staff/Manager của cửa hàng**, tôi muốn tạo đơn nhanh và ghi nhận tiền mặt, chuyển khoản hoặc kết hợp đúng số thực thu để không tính nhầm tiền/đơn.
- **Là Staff**, tôi muốn thấy tiền cần thu, tiền khách đưa, tiền thối, khoản chuyển đã có bằng chứng và khoản còn chờ đối soát để xử lý tại quầy.
- **Là Manager**, tôi muốn đối chiếu hóa đơn, ca và lịch sử thu theo cửa hàng với lý do xác nhận thủ công rõ ràng.
- **Là Customer**, tôi muốn không ai dùng dữ liệu client hoặc callback giả để đánh dấu đơn của tôi đã thanh toán.

### 2. Công việc

- Rà đường `POST /api/orders/counter/preview` → `POST /api/orders/counter` → Payment/receipt; backend phải tính lại giá món, ưu đãi, điểm, phí liên quan và `amountToCollect` từ bản nháp thật. Không tin `totalDue` hay `bankAmount` trong body Staff khi xác nhận thu.
- Bảo vệ store/actor: STAFF, STORE_MANAGER chỉ thao tác đơn đúng `storeId` trong phiên; ADMIN theo policy chọn quán, không vượt state machine. CUSTOMER không gọi được các endpoint thu quầy dù đổi header hoặc giả `storeId`.
- Duy trì `Idempotency-Key` bền theo draft ở `/counter`, dùng draft hash/fingerprint cho các thay đổi giỏ, điểm, voucher, phương thức. Cùng key + payload trả đúng order cũ; cùng key + payload khác conflict; timeout/nhấn đôi không tạo 2 đơn hay 2 receipt. Mở session/ghi thu có khóa idempotency riêng theo operation.
- Với **CASH**, validate tiền mặt nhận/đã thu/tiền thối bằng số nguyên VND; receipt ghi theo nguồn và actor. Thu đủ/thiếu được xử lý theo quy tắc nghiệp vụ C4, không tự chuyển thiếu thành discount, bán chịu hoặc refund.
- Với **TRANSFER/BANK_TRANSFER**, chỉ khởi tạo `counter-bank-transfer` cho đơn dương có phương thức hợp lệ. Server chọn tài khoản nhận theo cấu hình cửa hàng, sinh ref chính xác, gắn `orderId/storeId/amount`, hạn thanh toán, `Idempotency-Key` UUID; xác nhận ngân hàng dựa feed SePay đã xác thực, không dựa click “Đã chuyển”.
- Với **SPLIT**, tiền cash cố định ngay khi khởi tạo phiên transfer; phần bank = tiền phải thu − cash. Các nguồn được lưu riêng; chuyển khoản do Staff khai nhưng chưa có bankTx phải ở trạng thái chờ đối soát, không tạo giao dịch ngân hàng giả hoặc đếm trùng khi webhook đến.
- `/counter-collection` nhận `orderId, orderCode, queueNumber, storeId, totalDue, method, cashAmount, cashTendered, bankAmount, bankRefCode, manualAcceptance, exceptionReason, staffNotes` theo DTO hiện hành; kiểm lại snapshot Order, version/state, nguồn tiền, chênh lệch và giới hạn quyền ở Payment, trả receipt đọc được sau timeout.
- Nếu cho phép **manualAcceptance** khi chưa có sao kê, yêu cầu lý do và audit actor/session; ghi rõ `pendingReconciliation`, chênh lệch, số Staff khai. Không dùng bản ghi này làm bằng chứng bank đã đối soát hoặc tự mở refund chuyển khoản.
- Webhook SePay: xác thực nguồn, unique theo giao dịch thực, không tái phân bổ một bankTx sang hai đơn; nhận nhiều khoản hợp lệ theo C3. Callback trùng, đến muộn, dư/thiếu, sai mã hoặc sai tài khoản được ghi/trình bày theo ledger, không tự tăng doanh thu khi đã thu.
- Đồng bộ `paymentStatus` và `collectionMethod` của Order sau Payment commit qua contract nội bộ/event bền; thu tiền **không** tự chuyển Order sang `COMPLETED` (bàn giao do B3). Hủy/hết hạn/void/print/shift summary và các retry phải giữ trạng thái/receipt/audit nhất quán.
- Với quote cuối **0đ**, chuyển sang `NO_PAYMENT/COLLECTED` của BE-88: không tạo bank transfer, collection/receipt 0đ hoặc thông báo “thu thất bại”; khi giỏ đổi thành 1đ phải trở về flow thu bình thường.
- Cập nhật thông báo lỗi theo API chuẩn, contract Order–Payment–POS và checklist đối chiếu dữ liệu Order/Payment/report/receipt, thêm migration Flyway mới nếu cần; không sửa migration đã chạy.

### 3. API

| API / trạng thái | Quyền, dữ liệu và quy tắc |
| --- | --- |
| **Hiện có** `POST /api/orders/counter/preview` | Staff/Manager/ADMIN qua Gateway và Order scope; body `CreateCounterOrderRequest`, trả quote backend trước commit. |
| **Hiện có** `POST /api/orders/counter/draft-hash` | Cùng body draft; phục vụ fingerprint của bản nháp POS; không tự coi hash là xác nhận giá/thanh toán. |
| **Hiện có** `POST /api/orders/counter` | Staff/Manager/ADMIN đúng store; yêu cầu `Idempotency-Key` hợp lệ theo service, create đúng một Order. |
| **Hiện có** `POST /api/orders/{id}/counter-payment-session`, `GET /api/orders/{id}/payment-context` | Order điều phối session/context; từ chối đơn 0đ, sai store/state; không để client tự chọn giá/tài khoản ngân hàng. |
| **Hiện có** `POST /api/payments/{orderId}/counter-bank-transfer` | STAFF/STORE_MANAGER/ADMIN; header `Idempotency-Key` UUID, body `cashAmount` VND; chỉ transfer hoặc split số dương theo guard Payment. |
| **Hiện có** `POST /api/payments/counter-collection` | STAFF/STORE_MANAGER/ADMIN; body `CounterCollectionRequest`; Payment xác minh Order, store, breakdown, idempotency; trả `CounterPaymentReceiptResponse`. |
| **Hiện có** `GET /api/payments/{orderId}`, `GET /api/payments/transactions` | Đọc trạng thái payment và feed theo bảo vệ role/store; không tự xem được ledger quán khác. |
| **Hiện có** `POST /api/payments/receipts/{id}/void`, `/print`, `GET /api/payments/shift-summary` | STAFF/STORE_MANAGER/ADMIN; void/print và tổng kết ca theo store/role, audit theo nghiệp vụ. |
| **Đường tích hợp hiện có** SePay webhook (đường thực theo `SepayWebhookSecurity.PATH`) | Dành cho provider đã xác thực, tuyệt đối không mở một route public “confirm paid” cho Customer/POS. |
| **Contract cần chốt** readback trạng thái đối soát/manual acceptance | Nếu DTO hiện tại chưa biểu đạt đủ pending/discrepancy/bank evidence thì mở rộng response hiện có theo C3/C4; chưa khẳng định endpoint mới đã tồn tại. |

### 4. Tiêu chí hoàn thành

- Staff quán A tạo POS hợp lệ → quote từ server bằng Order persist; tổng Order, Payment và POS giống nhau theo VND.
- Staff quán B/Customer dùng orderId quán A để tạo collection hoặc session → 403/404 phù hợp; không lộ tiền, ref, receipt.
- ADMIN thao tác quán được chọn hợp lệ → dữ liệu store đúng; role header/body giả không vượt policy.
- CASH đơn 200.000đ, khách đưa 250.000đ → receipt cash 200.000đ và tiền thối 50.000đ, không sinh refund.
- TRANSFER đơn 200.000đ → QR/ref số dương đúng tài khoản quán và `orderId`; chưa có sao kê thì chưa tự `PAID`.
- SPLIT 200.000đ = 120.000 cash + 80.000 bank → hai nguồn riêng, không đòi statement 200.000.
- Chuyển 70.000 + 50.000 cho đơn 200.000 → thiếu 80.000; nhận thêm 100.000 → dư 20.000 lưu riêng, chỉ một transition xác nhận.
- Staff đánh dấu manual acceptance không có `exceptionReason` → lỗi validation; có reason thì lưu pending và audit, không tạo bankTx giả.
- Webhook hợp lệ đến sau khoản transfer Staff khai → chỉ bổ sung chứng cứ, không làm tổng đã thu tăng hai lần.
- Cùng webhook/provider transaction lặp, hoặc hai webhook đồng thời → một allocation, một success event; payload cùng ID khác số tiền không ghi đè ledger.
- Webhook sai mã quán, amount/ref hoặc nguồn chưa xác thực → không xác nhận payment của đơn khác; transaction không khớp vẫn có trace đối soát theo C3.
- Create/collect nhấn đôi hoặc timeout rồi retry **cùng** key/payload → cùng Order/receipt, không thu lần hai.
- Cùng `Idempotency-Key` nhưng đổi giỏ, voucher, điểm hoặc split cash → trả conflict; draft mới dùng key mới.
- Đơn đã hủy/hết hạn, QR hết hạn hoặc chuyển tiền đến sau hạn → không tự mở lại Order; tiền đến muộn có trạng thái xử lý riêng.
- Thay voucher/điểm làm tổng **0đ** → đi theo BE-88, không QR/receipt thu 0; làm tổng thành **1đ** → payment dương bình thường.
- Receipt được void/in lại hoặc xem tổng kết ca → lịch sử thao tác, nguồn thu và store nhất quán, không mất audit/gộp nhầm quán.
- Payment thành công nhưng Order chưa bàn giao → chưa `COMPLETED`; report/loyalty không cộng hoàn thành hay thu hai lần.
- Bàn giao contract, mã lỗi, mẫu request/response, lưu đồ case SePay chậm và kết quả nghiệm thu thực tế kèm giới hạn đã biết.

### 5. Điểm cần chốt

- C3/C4 chốt điều kiện **manualAcceptance** cho đơn transfer/split đang thiếu sao kê: ai được ghi nhận, reason bắt buộc, biểu diễn discrepancy/pending và thời điểm Order được đi bếp.
- Chốt chính sách partial, overpayment, ngân hàng gửi trễ, QR hết hạn và hướng xử lý khoản dư/không rõ người gửi; không tự cấp refund.
- Chốt chính xác scope STAFF/STORE_MANAGER/ADMIN đối với void, in lại receipt, xem ca và xem giao dịch ngân hàng theo ma trận RBAC.
- Chốt việc một đơn WEB được thu tại quầy trường hợp nào theo C4; không đổi `orderSource=COUNTER` để vượt guard.
- Chốt loại dữ liệu receipt version và event Order–Payment khi hai máy thu đồng thời; không lấy UI local làm nguồn sự thật.
