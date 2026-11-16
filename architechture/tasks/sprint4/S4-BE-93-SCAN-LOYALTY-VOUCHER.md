## BE-93 — Quét mã QR tích điểm và áp mã voucher tại quầy theo đúng quyền và thời hạn

**Phụ trách:** Theo PIC hiện có trên Lark · **Sprint:** 4 · **Ước lượng:** 8 SP · **Time Estimate:** 14 giờ · **Priority đề xuất:** 1
**Phụ thuộc:** BE-91 (tích/đổi điểm), BE-92 (voucher POS), BE-87 (draft/quote POS), Customer `/loyalty`, Staff CounterOrderModal, phiên Loyalty service.

### 1. User Story

- **Là Customer**, tôi muốn khi mở trang Loyalty được cấp mã chữ và QR riêng chỉ sống 30 giây, tự đổi khi còn ở trang và vô hiệu khi rời, để đưa cho Staff đúng lúc giao dịch.
- **Là Staff**, tôi muốn dùng camera quét đúng QR Loyalty hoặc QR voucher và nhập thủ công khi cần, nhận thông báo rõ khi mã hết hạn/sai loại để áp được đúng ưu đãi.
- **Là hệ thống**, tôi muốn dùng mã chữ chỉ cho tích điểm, chỉ dùng scan-proof từ QR Loyalty còn hiệu lực để đổi điểm, còn voucher dùng theo chính sách bearer code độc lập, để mọi API giữ đúng ranh giới quyền.

### 2. Công việc

- Phân loại **hai định dạng QR khác nhau** trước khi gọi API: `CLUCKY:LOYALTY:V1:<opaqueToken>` dành cho phiên Loyalty động; `CLUCKY:V1:<voucherPayload>` dành cho mã voucher cá nhân. Backend kiểm tiền tố, phiên bản và loại token, không cho dùng chéo hoặc nâng quyền bằng field client tự khai.
- Chỉ khi CUSTOMER có JWT thật và mở trang `/loyalty` thì gọi open session; server cấp một cặp **mã Loyalty chữ/số để nhập** (`EARN_ONLY`) và **QR opaque** (`EARN_REDEEM`), gắn account/session/generation/expiry. Không sinh token khi login, vào `/account`, giữ trên profile hay làm background job không có phiên.
- Mỗi chu kỳ **30 giây tính theo server clock** phát mã/QR mới, hết hạn mã cũ đúng `expiresAt`, không có grace window. Token phải ngẫu nhiên mạnh, lưu verifier/hash trong kho TTL, không log plaintext/copy vào analytics hay URL.
- Giữ session bằng heartbeat ngắn hạn và lease dự phòng; rời route, logout, đổi account/đóng màn phải cố gắng close/revoke. Tab ẩn có thể che QR nhưng **không thu hồi ngay** để mã vừa hiện vẫn sử dụng được trong 30 giây của nó. Khi mất mạng/đóng browser đột ngột, server tự hết lease.
- Staff cần `draftHash` do Order cấp từ giỏ hàng, chi nhánh và khách, không để JS tự chọn hash tùy ý. Quét QR Loyalty hợp lệ: kiểm session ACTIVE, token chưa hết hạn, generation chưa thay, actor Staff và `storeId` hợp lệ; cấp `scanProof` ngắn hạn một lần gắn customer/wallet, Staff, store và draft.
- Preview với scan-proof chỉ tính quote và điểm có thể dùng, chưa consume proof hoặc debit ví. Khi create, Order/Promotion xác thực proof đủ hạn/đúng draft, khóa ví và reserve điểm duy nhất, idempotent theo order. Proof có TTL riêng; QR gốc hết hạn sau khi đã scan không làm mất proof đang sống nếu session còn active.
- Đổi món, store, khách hoặc order draft làm proof không khớp và phải quét QR mới; đổi số `pointsUsed` trong hạn mức của cùng draft chỉ cần quote lại, không quét lại nếu proof chưa hết hạn.
- **Mã Loyalty chữ/SĐT/email gõ tay chỉ có EARN**. Backend cưỡng chế `pointsUsed=0` khi không có scan-proof; không cấp proof từ code chữ, `scanned=true`, từ QR đã hết hạn hoặc từ request tự giả camera.
- Với QR voucher, Staff nhập code hoặc scan đều gọi validation theo BE-92: đúng mã được giảm mà **không cần OTP hay kiểm chủ voucher tại POS**. Voucher không đổi mỗi 30 giây và không bao giờ mở quyền dùng điểm Loyalty.
- Scanner trong Staff POS dùng `getUserMedia` với nút mở/đóng, chọn camera trước/sau nếu có, thông báo không cấp quyền/không có camera/trình duyệt không hỗ trợ, ngừng media tracks khi đóng, reload hoặc đổi tab/route, debounce khi camera đọc trùng nhiều frame.
- UI phân hai nhóm “Tích điểm/Loyalty” và “Voucher”, phân biệt nhãn trạng thái “Đã quét Loyalty — có thể dùng điểm”, “Nhập tay — chỉ tích điểm”, “Áp voucher”; không để cả hai scanner gán kết quả vào cùng input hoặc giữ proof sau khi đổi quán.
- Đảm bảo rate limit theo account/session/Staff/store/mã; event/audit lưu actor/store/order/time/result nhưng che token thô. Các lỗi như invalid, expired, session revoked, wrong store, draft changed, proof used cần trả theo contract error catalog và UI giải thích cách quét lại.
- Tích hợp số tiền cuối cùng với Order preview/create, payment và receipt; khi voucher + điểm đưa số tiền về 0đ, không mở QR ngân hàng. Không phát điểm hoặc voucher usage chỉ bằng việc scan thành công.

### 3. API

| API / contract | Quyền / yêu cầu |
| --- | --- |
| `POST /api/loyalty/live-session/open` | CUSTOMER đăng nhập; trang `/loyalty` mở thì cấp cặp code/QR và thời gian server/expiry |
| `POST /api/loyalty/live-session/heartbeat` | CUSTOMER sở hữu session; gia hạn presence lease, không kéo dài hạn QR đã phát |
| `POST /api/loyalty/live-session/rotate` | CUSTOMER sở hữu session; cấp generation mới sau chu kỳ 30s và vô hiệu mã cũ |
| `POST /api/loyalty/live-session/close` | CUSTOMER sở hữu session; thu hồi khi rời màn/logout, server còn lease timeout dự phòng |
| `POST /api/orders/counter/draft-hash` | Staff đúng store; tạo draftHash từ order request phía server để bind bằng chứng scan |
| `POST /api/loyalty/counter/loyalty/scan` | STAFF/STORE_MANAGER/ADMIN đúng store; body `qrToken`, `storeId`, `draftHash`; validate QR động và trả scan-proof |
| `POST /api/loyalty/counter/lookup` | STAFF đúng store; tra PHONE/EMAIL/CODE để tích điểm; tuyệt đối không trả proof quyền dùng điểm |
| `POST /api/orders/counter/preview` | Staff đúng store; quote voucher/points theo proof đã quét, không reserve/debit |
| `POST /api/orders/counter` | Staff đúng store, `Idempotency-Key`; kiểm lại proof và reserve Loyalty/voucher đúng một lần theo order |
| `POST /internal/loyalty/counter/preview`, `/reserve` | Chỉ Order/internal service đã xác thực; kiểm points, wallet, proof, store và draft tại thời điểm thực hiện |
| `POST /api/promotions/vouchers/validate` | Dùng cho voucher code/QR; kiểm mã và campaign, **không cấp quyền Loyalty** |

- Đây là các route hiện có trong controller; nếu cần bổ sung error fields/TTL metadata phải cập nhật DTO/API. Không coi cờ `inputMethod=SCAN` tự khai là bằng chứng scanner thật: bí mật token mới xác định phạm vi quyền.

### 4. Tiêu chí hoàn thành

- Customer đăng nhập nhưng chưa mở `/loyalty`: Không có QR đang hoạt động được phát trước; Staff không thể tự mở phiên Loyalty của người khác.
- Customer mở trang `/loyalty`: Nhận mã chữ và QR riêng của account, có countdown dựa serverTime; không lưu mã trong localStorage/sessionStorage/URL.
- Đủ 30 giây kể từ issuedAt: Mã/QR generation trước hết hạn; scan ngay sau expiry trả lỗi rõ “Mã hết hạn — vui lòng mở mã mới”, không dùng grace window.
- Customer chuyển sang tab khác khi mã mới cấp còn 15 giây: Tab có thể che QR nhưng session vẫn có lease; mã chưa hết hạn quét được nếu session vẫn ACTIVE.
- Customer rời route, logout hoặc đổi account: Mã cũ và proof chưa reserve bị vô hiệu; reload lại cần phiên mới; sau browser crash lease timeout cũng vô hiệu hóa.
- Customer mở `/loyalty` trên hai thiết bị: Quy tắc session cạnh tranh theo contract nhất quán, không có hai bộ token active ngoài policy.
- Staff nhập SĐT/email/mã Loyalty chữ đúng: Chỉ có quyền EARN; request trực tiếp `pointsUsed>0` bị bác dù có sửa JS hoặc gọi API thủ công.
- Staff quét QR Loyalty sống của account A tại quán A với draft hợp lệ: API cấp proof và POS mở phần dùng điểm; preview vẫn không thay số dư.
- Staff quét Loyalty QR đã rotate hoặc sai loại token: Backend từ chối, không cấp proof, không tiết lộ thông tin tài khoản khác.
- Staff dùng proof đúng, chọn 20 điểm và tạo order thành công: Reserve 20 một lần; cùng order retry không tiêu điểm lần hai.
- QR gốc hết hạn sau lúc scan nhưng proof còn TTL và session còn ACTIVE: Reserve được theo proof; quá TTL proof thì yêu cầu scan mới.
- Staff đổi giỏ, cửa hàng, khách hoặc loại đơn sau khi scan: `draftHash` mismatch, proof không còn áp dụng; không giữ quyền đổi điểm cho đơn khác.
- Staff chỉ đổi `pointsUsed` trong cùng draft khi proof sống: Server tính quote mới và không bắt quét lại nếu đủ điều kiện.
- Hai POS cùng cố sử dụng một QR/proof: Tối đa một reservation hợp lệ; replay proof hoặc double submit trả lại kết quả idempotent cho cùng order, conflict cho order khác.
- Staff scan QR voucher `CLUCKY:V1` trong khối voucher: Được giảm theo campaign khi hợp lệ; không mở quyền dùng Loyalty points.
- Staff scan QR Loyalty ở vùng voucher hoặc QR voucher ở vùng Loyalty: Báo sai loại có chỉ dẫn, không tính giảm hoặc cấp proof sai quyền.
- Staff nhập mã voucher cá nhân đúng thay vì scan: Voucher vẫn áp được, không OTP hoặc owner check; nếu không quét Loyalty thì điểm vẫn earn-only.
- Khách từ chối quyền camera, camera bận, thiếu HTTPS hoặc không có thiết bị: UI báo lỗi hữu ích và có phương thức nhập tay trong đúng phạm vi quyền; không giả “đã quét”.
- Đóng modal scanner/chuyển route/reload: Media tracks được stop; không mở nhiều camera nền hoặc chụp sau khi dialog đã đóng.
- Camera đọc một QR qua nhiều frame: Chỉ gửi một request xử lý tại thời điểm scan; không tạo nhiều proof/discount do double-scan.
- Mã bí mật trong QR bị cố truyền vào analytics, URL, log backend hoặc response error: Không lưu/hiển thị giá trị nguyên; log nghiệp vụ chỉ có mã tham chiếu đã che và actor/store.
- Voucher + Loyalty tạo bill 0đ hoặc 1đ: Quote/receipt/payment thống nhất, bill 0đ không có VietQR/thu giả; một đồng dương vẫn xử lý phương thức thanh toán bình thường.
- Staff cửa hàng B cố dùng `storeId` A, proof của nhân viên A hoặc đơn A: Authorization backend từ chối, không thay đổi ví, voucher hay order.
- Bàn giao mô tả QR format/TTL, DTO/error, RBAC, ma trận scanner input, kết quả kiểm thử permission/race/idempotency, commit và bằng chứng; nêu phần bị chặn.

### 5. Điểm cần chốt

- Owner Loyalty xác nhận một hay nhiều phiên QR active/account, heartbeat và lease, TTL proof sau quét và cách xử lý tab nền bị browser throttle; giữ nguyên chu kỳ mã 30 giây đã chốt.
- Chốt đầy đủ field `draftHash`: biến đổi nào sẽ làm proof mất hiệu lực; **không** cho việc đổi số điểm cùng draft phá proof vô lý.
- Chốt QR voucher serializer thực tế và quy tắc normalizing mã để không ghi nhầm mã Loyalty thành Voucher; mã cá nhân không rotate 30 giây.
- Chốt danh sách lỗi chính thức trong `contracts/errors.json`, rate limit scan/lookup, cách che mã trong audit và thông báo người dùng khi camera lỗi.
- Xác nhận thiết bị/quyền camera và HTTPS cho môi trường dùng thật tại quầy; backend không tự khẳng định có camera thật chỉ vì nhận được payload QR.
