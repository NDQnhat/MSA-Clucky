## BE-89 — Email bàn giao và khôi phục hai tài khoản Manager/Staff khi tạo chi nhánh

**Phụ trách:** Chưa phân công · **Sprint:** 4 · **Ước lượng đề xuất:** 3 SP (6 giờ)
**Phụ thuộc:** A1/A2 cấp cặp tài khoản chi nhánh; BranchProvisioning Catalog; Identity challenge/outbox; Notification email templates/ledger và cấu hình Gmail App Password.
**Ưu tiên đề xuất:** 2/5 · **Phạm vi:** Backend / Catalog + Identity + Notification, phối hợp portal React.

### 1. User Story

- **Là Admin**, khi tạo một cửa hàng, tôi muốn hệ thống cấp đúng một tài khoản Manager và một tài khoản Staff, đồng thời có thể xem trạng thái bàn giao qua email và retry hợp lệ.
- **Là người nhận email khôi phục của chi nhánh**, tôi muốn hai email nêu rõ login, vai trò, mã kích hoạt và cách đặt mật khẩu lần đầu cho **từng tài khoản** dù dùng chung một hộp thư.
- **Là Manager/Staff**, tôi muốn yêu cầu lại mã kích hoạt hoặc khôi phục mật khẩu của đúng account; thao tác trên một account không ảnh hưởng password/OTP/phiên của account kia.
- **Ranh giới bảo mật:** Không gửi mật khẩu plaintext/mật khẩu chung trong email, Kafka, DB hoặc log; giữ quy trình activation + người dùng tự đặt mật khẩu đã phê duyệt.

### 2. Công việc

- Rà chuỗi `POST /api/stores` → `BranchProvisioning.create/synchronize` → Identity `PUT /api/auth/internal/branch-accounts/{storeId}` có HMAC → `BranchAccountService.provision`. Giữ idempotency theo `storeId/requestKey/payload`; khi provision đủ tạo **đúng hai** login ổn định `manager.<storeId>` và `staff.<storeId>`, role `STORE_MANAGER/STAFF`, dùng chung `recoveryEmail` nhưng accountId/challenge khác nhau.
- Chỉ chuyển readiness của Catalog khi Identity trả cặp đúng và điều kiện vận hành đã đủ; không báo “quán sẵn sàng” vì chỉ lưu quán. Khi Identity lỗi, giữ trạng thái có thể retry mà không xóa cửa hàng, nhân bản account hoặc tạo role thứ ba.
- Mỗi account mới có challenge `ACTIVATE` riêng, code **8 chữ số**, `challengeId`, account/role/store/purpose/expiry; challenge hết hạn hoặc gửi lại không được kích hoạt password cũ. Người nhận vào `BranchAccessPage`, nhập đúng login/challenge/OTP rồi tự đặt password theo policy.
- Rà `BranchAccountOutbox` → Kafka `identity.branch-email` → Notification security mail → `email_logs/email_attempts` → SMTP. Chỉ log trạng thái/envelope/diagnostic có che thông tin; tách **đã enqueue**, **SMTP đã chấp nhận (SENT)** và **người dùng đã kích hoạt**. SMTP accepted không chứng minh mail đã đến inbox.
- Template `BRANCH_ACTIVATE` từ migration hiện có `V15__branch_account_onboarding_email_copy.sql` cần giữ rõ cửa hàng/mã cửa hàng nếu nguồn Catalog tin cậy, role, login, challengeId, OTP, hạn tuyệt đối theo giờ Việt Nam, các bước tự mở trang quản lý và đặt mật khẩu. Phù hợp `Email_Templates_2026-11-12.md`: security mail không dùng nút/CTA/link mở app, có text và HTML thoát ký tự.
- Tách `BRANCH_RESET` (account đã activated) khỏi `BRANCH_ACTIVATE`. Request RESET chỉ gửi recovery email đã đăng ký cho đúng target login, không tin email tự khai trong request; mã RESET không kích hoạt account mới và không reset account còn lại. Phân biệt `RECOVERY`, `ADMIN_RESET`, `ADMIN_RECOVERY` theo hợp đồng A2, từng bước xác thực lại/OTP đúng kênh.
- Rà thao tác `POST /api/auth/branch/challenge` và `/branch/finish`: giới hạn gửi/thử, challenge một lần, expiry, mục đích/account/branch version; finish mới đổi password và revoke session của **đúng account**; đối với recovery email đổi chung, áp version/revocation cho cặp theo A2.
- Với Admin thao tác hỗ trợ, `/branch/accounts/{id}/challenge` và `/branch/management/finish` phải ràng buộc phiên Admin/Manager đúng quán, mật khẩu xác thực lại, role/purpose và challenge; STAFF không có quyền quản lý recovery email. Cấm endpoint client thường gọi được internal provision.
- Lưu email event và frozen snapshots idempotent theo messageId/fingerprint. Retry provider theo `Email_Operations` chỉ cho lỗi kết nối an toàn; **không dùng manual retry email-log để gửi lại OTP security** hoặc mã đã hết hạn: bắt đầu challenge mới từ Identity với rate limit. Trường hợp SMTP outcome unknown không gửi trùng mù.
- Trên Admin UI thêm/đối chiếu `accounts-status`, tài khoản activated/chưa activated và email ledger không chứa secret; thao tác retry Catalog hoặc challenge account đúng quy tắc. Thông báo riêng khi quán tạo được nhưng email không enqueue, gửi lỗi, hoặc activation chưa xong.
- Dùng migration Flyway **mới** nếu cần thay template/metadata; không sửa V12–V15 đã áp. Bàn giao tài liệu biến môi trường placeholder, nhóm lỗi, luồng recovery và checklist kiểm tra inbox thực tế.

### 3. API

| API / trạng thái | Quyền, dữ liệu và quy tắc |
| --- | --- |
| **Hiện có** `POST /api/stores` | ADMIN; `Idempotency-Key` UUID, `StoreDto.Request` gồm recovery email theo contract; Catalog tạo quán và điều phối cấp cặp. |
| **Hiện có** `GET /api/stores/{id}/accounts-status` | ADMIN hoặc STORE_MANAGER **đúng store**; trả provisioning/readiness metadata, không OTP/password. |
| **Hiện có** `POST /api/stores/{id}/accounts-retry` | ADMIN; retry provision của store đó, cùng định danh cặp account; không tạo user mới nếu đã commit. |
| **Hiện có** `GET /api/auth/branch/stores/{storeId}` | ADMIN hoặc STORE_MANAGER đúng store; trả `Pair` với `accountId,role,login,status,activated` và version/readiness, không secret. |
| **Hiện có** `POST /api/auth/branch/challenge` | Login + purpose `ACTIVATE/RESET`; public có phản hồi chống dò tồn tại, server tự tìm recovery destination; challenge tách account. |
| **Hiện có** `POST /api/auth/branch/finish` | Body `challengeId,otp,newEmailOtp?,password?`; public nhưng bắt buộc challenge đúng và còn hạn; đặt/reset password từng account. |
| **Hiện có** `POST /api/auth/branch/accounts/{id}/challenge`, `POST /api/auth/branch/management/finish` | ADMIN/STORE_MANAGER theo guard nội bộ; re-auth, role/store/session/purpose bound; thao tác admin-assisted/recovery không dành Staff. |
| **Nội bộ hiện có** `GET/PUT /api/auth/internal/branch-accounts/{storeId}` | Chỉ Catalog/consumer được HMAC xác thực bằng timestamp/signature; không mở qua UI/Gateway như API quản trị thường. |
| **Hiện có** `GET /api/notifications/email-logs`, `GET /api/notifications/email-logs/{id}` | ADMIN đọc toàn bộ; Manager chỉ email không nhạy cảm của quán mình theo `Email_Operations`; security branch mail global/private không lộ Manager. |
| **Hiện có, không dùng resend OTP** `POST /api/notifications/email-logs/{id}/retry` | ADMIN và ledger chỉ cho failure business đủ điều kiện; email OTP/security không có quyền retry theo endpoint này. |
| **Hợp đồng event hiện có** `identity.branch-email` → `BRANCH_ACTIVATE/RESET` | Producer cung cấp context tin cậy và expiration; Notification render/freeze, không đưa code vào API ledger; không suy đoán email đến inbox từ SENT. |

### 4. Tiêu chí hoàn thành

- Admin tạo quán hợp lệ với một recovery email → đúng **hai** account/role/login `manager.<id>`, `staff.<id>`; không tạo mật khẩu ban đầu chung.
- Hai email ACTIVATE gửi đến cùng mailbox → subject/nội dung phân biệt login/role, challengeId/OTP riêng, chỉ dẫn đặt password, hạn theo giờ Việt Nam.
- Template security HTML/text hiển thị logo/tên thương hiệu theo quy chuẩn, thoát input, không chứa nút CTA hoặc mật khẩu plaintext.
- Nhập OTP/challenge Manager đúng → chỉ Manager activated và đặt password; Staff vẫn chưa activated.
- Dùng OTP Manager để finish Staff hoặc sửa login/accountId mục tiêu → bị từ chối, không thay password/scope.
- OTP sai, hết hạn, đã dùng, sai purpose hoặc không khớp branch version → `INVALID_OTP/OTP_EXPIRED/RESOURCE_CONFLICT` theo contract, không hoàn tất sai.
- Challenge RESET tài khoản đã activated → gửi đúng recoveryEmail/server-chosen destination, nội dung RESET khác ACTIVATE; không reset Staff khi target Manager.
- Gửi lại OTP cho một account → challenge cũ hết quyền, không tạo account thứ ba, có rate limit và audit.
- Create Store mất response sau commit, retry cùng idempotency key → trả quán/cặp cũ, không nhân email/challenge vô cớ.
- Hai request provision đồng thời hoặc thay payload với cùng key → một cặp account; payload khác conflict, Catalog readiness không bị ghi đè.
- Identity chưa provision đủ hoặc đang lỗi → Catalog báo trạng thái thực và cho Admin retry, không tự chuyển sang OPEN giả.
- Kafka tạm lỗi trước enqueue → trạng thái pending/retry durable; không báo SENT khi chưa có ledger Notification.
- SMTP trả lỗi auth hoặc timeout → giữ `FAILED/SMTP_OUTCOME_UNKNOWN` chính xác, không coi accepted là delivered; không lộ SMTP credential.
- Security email bị EXPIRED/unknown → không manual retry từ email logs; request challenge mới theo OTP service là đường resend.
- Staff cố đọc email logs, challenge quản trị hoặc internal provision → 403; Manager khác quán không xem được account/status của quán khác.
- Admin hỗ trợ RESET mà thiếu re-auth/OTP Admin/session khớp → từ chối; thao tác đúng mới revoke phiên target phù hợp.
- Quản lý hồi phục email chung có hai mã đúng kênh → chưa đủ hai bước không thay recoveryEmail; thay thành công cập nhật version và revoke session theo A2.
- Audit/status/ledger chỉ có thông tin an toàn; bàn giao đủ API/RBAC, dòng lỗi, template mới (nếu có) và bằng chứng nhận email thực tế do người dùng nghiệm thu.

### 5. Điểm cần chốt

- Chốt dữ liệu **tên cửa hàng/mã cửa hàng** tin cậy được Catalog đưa vào email; khi thiếu thì hiển thị mã định danh, không tự dựng tên từ login.
- Chốt đường truy cập thủ công đến trang `BranchAccessPage` trong hướng dẫn: security email hiện **không có link/CTA**, vì vậy mô tả text phải rõ nhưng không tự thêm nút.
- Chốt thời hạn challenge, giới hạn resend, bộ phận xử lý thư SMTP_OUTCOME_UNKNOWN và cách đối chiếu thư thực nhận khi SMTP chỉ báo SENT.
- Chốt ai có quyền xem **status email bảo mật**/vận hành chi nhánh mà không lộ địa chỉ, OTP hoặc chi tiết global/private; theo BE-63 và ma trận RBAC.
- Chốt trạng thái readiness khi cặp đã provision nhưng một/hai account chưa activated, và điều kiện OPEN kết hợp quy tắc Catalog hiện hành.
