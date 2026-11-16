## BE-91 — Tích điểm khi Staff đặt hàng nhanh và giới hạn tích điểm thống nhất giữa POS và Web

**Phụ trách:** Theo PIC hiện có trên Lark · **Sprint:** 4 · **Ước lượng:** 5 SP · **Time Estimate:** 9 giờ · **Priority đề xuất:** 1
**Phụ thuộc:** BE-37 (chính sách và ví Loyalty), BE-87 (đơn nhanh và thanh toán), BE-93 (QR Loyalty), API Order–Promotion.

### 1. User Story

- **Là Staff**, tôi muốn nhập SĐT/email/mã Loyalty để cộng điểm cho đúng khách sau khi đơn thực sự hoàn tất, kể cả khách chưa có tài khoản, mà không bắt khách làm OTP tại quầy.
- **Là Customer**, tôi muốn điểm được tính trên giá trị tiền hàng thực trả, sau voucher và điểm đã đổi, áp giới hạn minh bạch và không cộng lặp khi hệ thống retry.
- **Là Admin**, tôi muốn cấu hình trần tích theo đơn, theo ngày và theo số dư ở một nơi, áp dụng nhất quán trên Web/POS.

### 2. Công việc

- Rà `CounterOrderModal` → `POST /api/orders/counter/preview` → OrderService → Promotion/Loyalty → settle khi order hoàn tất. Đối chiếu cách tính với `/api/orders/preview` trên Web để không có hai công thức điểm.
- Chuẩn hóa tra ví qua `POST /api/loyalty/counter/lookup`: `PHONE` dùng SĐT E.164 Việt Nam, `EMAIL` chỉ tìm contact verified, `CODE` là mã chữ Loyalty còn hạn. SĐT guest có thể tích điểm nhưng không tạo tài khoản/OTP; không sinh ví guest bằng email.
- Staff chỉ nhập định danh sẽ có quyền **EARN ONLY**, kể cả khi tìm được account có số dư. Backend bắt buộc `pointsUsed=0` ở preview/create, từ chối việc client giả trường đã quét. Chỉ scan-proof hợp lệ từ BE-93 mới mở quyền REDEEM.
- Không tự động lấy `receiverPhone` của đơn để tích; Staff phải chủ động nhập hoặc quét; khi đổi khách/quán/giỏ reset trạng thái liên quan, không gắn nhầm ví của lượt bán trước.
- Xác định wallet `PHONE` hay `CUSTOMER` từ dữ liệu đã xác thực. Việc khách xác thực số tại Identity mới sinh sự kiện claim điểm PHONE→CUSTOMER một lần; giữ nguyên ledger cũ, không tạo điểm mới từ số đã chuyển.
- Lấy `spendPerPoint`, `pointValue`, ngưỡng hạng, `maxEarnPerOrder`, `maxEarnPerDay`, `maxPointBalance` và policy version từ service. Giá trị **0 = không giới hạn**, không coi là 0 điểm. Kiểm giá trị cấu hình không âm.
- Tính bằng BigDecimal/số nguyên theo thứ tự: `subtotal` tin cậy từ Catalog → giảm voucher → giảm hạng → điểm đã dùng → `earnBase = max(0, tiền hàng còn lại)`; loại phí ship và phần thanh toán bằng điểm khỏi giá trị tích. Không lấy số điểm do UI gửi làm kết quả.
- Trần ngày áp múi giờ `Asia/Ho_Chi_Minh`, tính số đã earn và đang hold; trần số dư tính cả số đang reserve để hai order đồng thời không vượt giới hạn. Ví cũ vượt cap sau claim không bị cắt số dư hợp lệ.
- `COMPLETED` cùng tình trạng thanh toán thực hợp lệ mới settle EARN/REDEEM. Order chưa thanh toán, hủy hoặc COD ship mock chưa thu thực không được tự cộng điểm vì sự kiện giả lập. Đơn miễn thu hợp lệ xử lý theo chính sách tiền hàng 0đ của BE-88.
- Idempotency bằng orderId/eventId/operation key trong consumer và reserve; duplicate webhook/event/retry không tăng điểm lần thứ hai, không âm ledger. Đơn hủy release hold, refund xử lý bút toán bù theo refundId.
- Trả quote gồm `pointsUsed`, `pointsAmount`, `pointsToEarn`, `availablePoints` khi đúng scope, `policyVersion`, `tierDiscount`. Hóa đơn và lịch sử hiển thị điểm dự kiến khác với điểm đã ghi nhận; không để Staff xem chi tiết nhạy cảm của ví chỉ từ SĐT.
- Nếu cần thay schema, dùng Flyway migration mới tại Promotion, giữ ID qua REST/event, không FK hoặc SQL join xuyên service; audit actor/store/order và che định danh.

### 3. API

| API / sự kiện | Quyền / yêu cầu |
| --- | --- |
| `POST /api/loyalty/counter/lookup` | STAFF/STORE_MANAGER/ADMIN đúng chi nhánh; `kind`, `value`, `storeId`; chỉ resolve ví để tích, response giới hạn PII |
| `POST /api/orders/counter/preview` | Staff đúng store; quote tổng tiền, điểm dự kiến, quyền dùng điểm, không reserve/settle |
| `POST /api/orders/counter` | Staff đúng store; `Idempotency-Key`, server tính lại quote, tạo đơn và giữ điểm/voucher nếu hợp lệ |
| `POST /internal/loyalty/counter/preview` | Chỉ service Order qua internal trust; input `loyaltySubject`, `scanProof`, `pointsUsed`, amount, staffId, storeId, draftHash |
| `POST /internal/loyalty/counter/reserve` | Chỉ Order; gắn `orderId`, policy version, giá trị đã quote, hold idempotent và kiểm proof để REDEEM |
| `GET /api/loyalty/policy`, `GET/PUT /api/loyalty/config` | Đọc policy theo contract, cập nhật config chỉ ADMIN, audit thay đổi |
| `GET /api/loyalty/me`, `GET /api/loyalty/claim-status` | CUSTOMER chủ ví; xem số dư/lịch sử và kết quả claim sau xác thực phone |
| `order.completed`, hủy, refund theo contract sự kiện | Promotion đối chiếu trạng thái/order/payment, settle hoặc release/compensate đúng một lần |

- Các route HTTP được đối chiếu với controller hiện tại. Nội bộ phải xác thực service và kiểm store/actor thực tế, không tin staffId do frontend tùy ý khai.

### 4. Tiêu chí hoàn thành

- Staff nhập SĐT guest chưa tạo account ở quán A rồi thanh toán và hoàn tất: Tích vào PHONE wallet một lần, không hỏi OTP, không tạo user.
- Staff nhập SĐT đã verified của Customer A: Ghi điểm vào CUSTOMER wallet đúng người; không tạo thêm ví PHONE song song.
- Staff nhập email account chưa verified: Không cho lookup để tích bằng email, không tạo guest wallet hay lộ thông tin account.
- Staff nhập mã Loyalty chữ còn hạn: Preview được EARN, nhưng request `pointsUsed=1` bị backend bác dù UI bị chỉnh.
- Không nhập/scan Loyalty: Đơn tạo bình thường và không cộng điểm do tự lấy receiverPhone hoặc dữ liệu lượt bán trước.
- Quét QR Loyalty còn hạn từ BE-93: Cho phép chọn điểm nguyên không âm, reserve đúng số dư; đổi số điểm trong quote hợp lệ không buộc quét lại khi proof còn sống.
- Order 100.000đ, voucher giảm 20.000đ, dùng 30.000đ điểm và phí ship 15.000đ: Earn base là 50.000đ, tiền thu 65.000đ; phí ship không tính điểm.
- `maxEarnPerOrder=10`, rawEarn=15: Chỉ được 10 điểm. Đổi cấu hình sang 0 thì không giới hạn bởi trần theo đơn.
- `maxEarnPerDay=20`, ngày hiện tại đã ghi/giữ 18: Chỉ còn 2 điểm được giữ, chuyển ngày theo giờ Việt Nam không tính nhầm múi giờ UTC.
- `maxPointBalance=100`, số dư cộng pending=98: Chỉ còn 2 điểm có thể earn; số dư vượt 100 do claim cũ vẫn giữ và không bị cắt âm thầm.
- Hai POS đồng thời tạo đơn cho cùng ví sát cap: Tổng earned+held không vượt trần, không có lost update hoặc double reserve.
- Preview rồi Admin đổi policy: Create phải dùng policy hiện hành hoặc trả conflict yêu cầu quote lại, không settle âm thầm theo giá trị stale.
- Kafka `order.completed` được gửi lại 10 lần: Ledger có một bút toán EARN/order, số dư không thay đổi ở lần thứ hai.
- Order chưa hoàn tất, hủy trước settle hoặc thanh toán thất bại: Không cộng điểm; hold được release đúng một lần.
- Đơn giao COD mô phỏng đã tới nơi nhưng chưa thu tiền thực: Không sinh điểm hay doanh thu ảo; trả trước thật mới settle theo chính sách.
- Guest có 35 điểm từ nhiều đơn, account có 80 điểm rồi xác thực SĐT hợp lệ: Claim tạo 115 điểm một lần, các giao dịch cũ và source order vẫn truy vết được.
- Refund có refundId bị replay: Chỉ một bút toán bù theo policy, không âm ví ngoài chính sách; voucher refund không được hoàn tự động.
- Staff cửa hàng B giả storeId A hoặc userId khác trên endpoint: Backend từ chối, không ghi điểm/hold cho account hoặc order ngoài phạm vi.
- Receipt, lịch sử Loyalty và Order detail sau refresh phản ánh đúng điểm đã dùng/dự kiến/đã settle từ server, không dùng local cache làm bằng chứng.
- Bàn giao contract, mã lỗi theo `contracts/errors.json`, kết quả nghiệm thu và bằng chứng đối chiếu ledger/order/payment, commit/môi trường; ghi rõ case chưa xác minh.

### 5. Điểm cần chốt

- PO chốt tỷ lệ tích/đổi, quy tắc làm tròn, trần tính cho toàn chuỗi hay từng store; không tự lấy số ví dụ làm chính sách thật.
- Chốt điều kiện `PAID/COLLECTED` cho COD mock, đơn 0đ và việc hoàn tiền một phần; không phát điểm theo nhãn thanh toán phía UI.
- Chốt giới hạn của account có ví guest merge, contact verified bị khóa và trường hợp SĐT trùng account; không tự chuyển ví đã merge sang account khác.
- Xác nhận error codes cho `NO_SCAN_PROOF`, cap conflict, policy stale, wallet merged trong catalog lỗi và luồng UI tương ứng.
- PIC đối chiếu phần đã có trong source với phần cần sửa để cập nhật effort nếu task chỉ còn nghiệm thu tích hợp.
