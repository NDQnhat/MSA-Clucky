## BE-86 — OTP SMS khi Customer đăng ký và xác thực số điện thoại tại tài khoản

**Phụ trách:** Chưa phân công · **Sprint:** 4 · **Ước lượng đề xuất:** 8 SP (14 giờ)
**Phụ thuộc:** Cấu hình Bridge SMS/provider được phê duyệt; Identity session/Gateway; event claim ví tích điểm; phối hợp S4-UI-52-SMS-OTP.
**Ưu tiên đề xuất:** 1/5 · **Phạm vi:** Backend / Identity và hợp đồng tích hợp Customer.

### 1. User Story

- **Là Customer mới**, tôi muốn đăng ký bằng số điện thoại Việt Nam, nhận OTP SMS và chỉ được kích hoạt phiên sau khi xác minh mã đúng để bảo vệ tài khoản.
- **Là Customer đã đăng nhập**, tôi muốn liên kết số điện thoại chưa được xác minh trong `/account` bằng OTP và xác thực lại mật khẩu để sử dụng tính năng gắn với số thật.
- **Là vận hành**, tôi muốn xem được trạng thái request challenge và nguyên nhân lỗi nhà cung cấp mà không truy cập OTP hoặc khóa API bí mật.
- **Ranh giới:** OTP Customer đăng ký/liên hệ tách khỏi email OTP cũ, mật khẩu/khôi phục và challenge kích hoạt Manager/Staff; không cấp role ngoài CUSTOMER.

### 2. Công việc

- Kiểm kê luồng hiện hữu ở `AuthController.register/resendOtp/verifyOtp`, `ProfileController`, `IdentityChallenges`, `BridgeOtpClient`, `PhoneNumbers` và Consumer claim loyalty. Đây là các đường đã có trong source; Sprint 4 phải rà tính nhất quán với UI-52, đặc biệt khi đăng ký chưa hoàn tất nhưng gửi SMS lỗi hoặc retry.
- Giữ đăng ký theo `fullName + phone + password`; server tự quyết `CUSTOMER`, `storeId=null`, `phoneVerified=false`. `PhoneNumbers.normalize` chuyển `0xxxxxxxxx` thành `+84xxxxxxxxx` và nhận số E.164; chặn số đã dùng ở cả dạng cũ và dạng chuẩn bằng unique DB/transaction khi hai người đăng ký đồng thời.
- Rà việc phát challenge `REGISTER` từ Bridge: lưu `challengeId` nội bộ, `userId`, số, purpose, `bridge_id`, expiry, used; code thật được xác minh qua Bridge, không lưu mã OTP dạng thô. TTL lấy thời điểm sớm hơn giữa expiry provider và cấu hình Identity. Không coi request SMS được provider chấp nhận là đã nhận/đã xác minh.
- Gửi lại qua request có `phone`: vô hiệu hóa challenge `REGISTER` chưa dùng cũ của đúng tài khoản; giữ account hiện có, không tạo bản ghi Customer mới. Bảo vệ rate limit theo số/purpose và account, thống nhất UX cho `429 TOO_MANY_REQUESTS`, `503 DEPENDENCY_UNAVAILABLE`, `502 UPSTREAM_ERROR`.
- Verify bắt buộc challenge thuộc đúng `userId`, đúng `phone`, `REGISTER`, còn hạn và chưa dùng; khóa row/consume trong transaction để đồng thời chỉ một request thắng. Mã sai, sai purpose hoặc challenge cũ không được cấp cookie; `phoneVerified` chỉ đổi khi Bridge xác minh thành công.
- Với `/account`, giữ `POST /api/users/me/phone/request` và `/verify`: gửi challenge `PHONE_BIND` cho **số muốn liên kết**, không lấy số cũ; chỉ Customer đã đăng nhập, `verify` yêu cầu `currentPassword` và ràng buộc challenge với `userId + số + purpose`. Tài khoản có `phoneVerified=true` hiện không được đổi số bằng luồng này; profile PUT không được tự đặt `phoneVerified`.
- Bảo vệ thay đổi trạng thái: challenge bị thay bởi resend/đổi số phải vô hiệu; reset/change-password với số cũ mất hiệu lực sau bind; không cho OTP trên tài khoản email legacy hoặc account Manager/Staff vô tình trở thành OTP đăng nhập.
- Khi xác minh hợp lệ, tăng `phoneBindingVersion`, ghi outbox `CustomerPhoneVerified` trong cùng transaction và bàn giao Promotion/Loyalty claim ví theo số **một lần**; retry/replay event không nhân ví/điểm.
- Tích hợp Gateway và frontend theo `X-Clucky-Client: customer`, HttpOnly cookie có thuộc tính theo `Browser_Sessions.md`. Thao tác request/verify không trả raw OTP, provider secret, hash, token nội bộ trong response/log/analytics; mask số ở log vận hành.
- Cập nhật DTO/status, thông báo theo mã lỗi chuẩn và biến cấu hình mẫu cho Bridge `base-url/api-key` (giá trị placeholder); mô tả bước môi trường thử SMS thật/giả lập, trace lỗi provider, migration Flyway **mới** nếu cần.

### 3. API

| API / trạng thái | Quyền, dữ liệu và quy tắc |
| --- | --- |
| **Hiện có** `POST /api/auth/register` | Public; `fullName,phone,password` (email mới không dùng khi đăng ký); tạo Customer chưa verify, trả `message,challengeId`. Chặn trùng số và payload tự khai role/store. |
| **Hiện có** `POST /api/auth/resend-otp` | Public; `phone` cho SMS REGISTER, `email` chỉ luồng legacy; rate limit, thay challenge cũ; response không tiết lộ OTP. |
| **Hiện có** `POST /api/auth/verify-otp` | Public challenge-bound; `phone,challengeId,otp` cho SMS; xác minh xong trả phiên Customer và Set-Cookie, mã sai không tạo phiên. |
| **Hiện có** `GET /api/users/me` và `PUT /api/users/me` | Customer authenticated; GET trả cờ `phoneVerified`; PUT sửa profile nhưng thay số trực tiếp bị chặn `OTP_REQUIRED/ALREADY_VERIFIED`. |
| **Hiện có** `POST /api/users/me/phone/request` | CUSTOMER; body `phone`; trả challengeId gửi đến số mới; kiểm trùng, giới hạn phát mã và khóa account scope. |
| **Hiện có** `POST /api/users/me/phone/verify` | CUSTOMER; body `phone,challengeId,otp,currentPassword`; xác thực lại mật khẩu, commit số/cờ verified đúng user; trả Profile. |
| **Hiện có** `GET /api/loyalty/claim-status` | Customer đã xác thực; dùng kiểm trạng thái claim loyalty, không coi là endpoint gửi/verify SMS. |
| **Hợp đồng nội bộ hiện có, cần đối chiếu** event Customer phone verified → Promotion/Loyalty | Outbox/consumer dựa trên account + phiên bản số; khóa idempotency để replay không nhân ví, không để client publish event. |
| **Đề xuất nếu thiếu khi tích hợp** trace gửi SMS an toàn | Chỉ metadata challenge/provider trạng thái, correlationId và mã lỗi; không thêm endpoint public kiểm tra OTP/secret. |

### 4. Tiêu chí hoàn thành

- Customer đăng ký với `09xxxxxxxx` hợp lệ → số chuẩn `+84...`, role CUSTOMER, chưa có phiên xác minh trước OTP.
- Số `+84...` tương đương `0...` đã có → `409 RESOURCE_CONFLICT`; hai đăng ký cạnh tranh chỉ tạo được một chủ sở hữu số.
- Body có `role=ADMIN`, `storeId` hoặc email đăng ký sai contract → từ chối hoặc bỏ trường theo DTO đã chốt; không nâng quyền.
- Provider trả challenge hợp lệ → API chỉ trả `challengeId` nội bộ, không trả OTP/hash/provider key; gửi SMS chưa đồng nghĩa verify.
- Provider timeout, 429 hoặc thiếu cấu hình → trả lỗi phụ thuộc/rate limit chính xác; không cấp phiên và có đường retry an toàn.
- Gửi lại cùng số → challenge REGISTER mới, challenge cũ không còn hiệu lực; không tạo user thứ hai.
- OTP đúng, đúng `phone + challengeId + purpose`, còn hạn → `phoneVerified=true`, cookie Customer và một outbox event.
- OTP sai, sai định dạng, quá hạn, replay sau dùng → lỗi `INVALID_OTP/OTP_EXPIRED`, không cấp cookie hoặc đổi cờ.
- Hai request verify cùng challenge → một commit, cùng số không phát hai lần claim/event có tác dụng.
- Lấy challenge của Customer A xác minh cho B hoặc đổi `phone` trong body → bị chặn, không liên kết số sai.
- Customer chưa xác minh bấm `/account` request số mới → challenge `PHONE_BIND` gắn **số mới**, không tự ghi số trước verify.
- Phone bind verify OTP đúng nhưng `currentPassword` sai → từ chối, không thay phone/phiên/loyalty; sai mật khẩu có rate limit.
- Số đã verify mà thử đổi trực tiếp qua PUT hoặc gửi lại PHONE_BIND → trả conflict theo policy, dữ liệu cũ giữ nguyên.
- Customer dùng OTP REGISTER cho PASSWORD_RESET/PHONE_BIND, hoặc nhân sự dùng OTP Customer → bị chặn đúng role/purpose.
- Replay Kafka sau xác thực hoặc binding lần hai → chỉ một ví/claim phù hợp số đã chứng thực; không phát sinh điểm ảo.
- UI-52 reload, resend, lỗi provider và 429 → giữ điều hướng đúng trạng thái, thông báo rõ, không lưu mã OTP vào telemetry.
- Audit/check log và DB → không có raw code, API key hoặc credential; bàn giao contract, schema/migration nếu có và kết quả kiểm thử thực tế (không coi Docker build là test nghiệp vụ).

### 5. Điểm cần chốt

- Chốt provider Bridge đã cấu hình production, chi phí/hạn mức SMS và chiến lược khi provider chấp nhận request nhưng SMS không tới; không tự chuyển sang SMS provider khác.
- Chốt thời hạn OTP, giới hạn gửi/xác minh, thời gian chờ gửi lại và cách hiển thị số bị che tại UI, dựa vào cấu hình vận hành cuối cùng.
- Chốt chính sách số điện thoại đã verified không được thay tại `/account` hay cần quy trình đổi số có xác thực riêng; không ngầm mở chức năng thay số.
- Chốt email legacy chưa verified và đăng ký mới chỉ bằng phone cùng tồn tại thế nào; không cho REGISTER SMS mở khóa account legacy.
- Chốt contract idempotency claim ví theo `accountId + phoneBindingVersion` và độ trễ xử lý event trước khi UI hiển thị claim.
