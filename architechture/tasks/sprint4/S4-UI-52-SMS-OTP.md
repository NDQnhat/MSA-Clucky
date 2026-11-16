## UI-52 (SMS OTP) — Đăng ký, gắn và xác thực số điện thoại bằng SMS tại Customer `/account`

**Phụ trách:** Chưa phân công (Frontend / Customer Next.js) · **Sprint:** 4 · **Ước lượng:** 5 SP (8 giờ đề xuất) · **Ưu tiên:** 2/5
**Phụ thuộc:** BE-86 (SMS OTP), Identity Profile/Session, Gateway; nhà cung cấp SMS và quy tắc cooldown/challenge do backend xác nhận.

### 1. User Story

- **Là Customer đã đăng nhập**, tôi muốn thêm số điện thoại Việt Nam vào tài khoản và xác minh bằng SMS OTP tại `/account` để có kênh liên hệ/khôi phục đã được chứng thực.
- **Là Customer đang đăng ký**, tôi muốn nhìn rõ bước OTP đăng ký và bước OTP xác thực số điện thoại trong hồ sơ, không bị nhầm mã email kích hoạt hoặc mã thay đổi mật khẩu với mã gắn số.
- **Là Customer có số đã xác thực**, tôi muốn biết số đó được bảo vệ, không thể thay trực tiếp chỉ bằng chỉnh form hoặc một phiên đăng nhập còn mở.

### 2. Công việc

- Đối chiếu luồng hiện có ở `frontend/nextjs/app/account/page.tsx`, `frontend/nextjs/lib/api.ts` và `ProfileController`; tận dụng form/account API đã có thay vì tạo endpoint giả. Phân biệt **OTP đăng ký tài khoản** ở `/api/auth/*` với **OTP bind số Customer** ở `/api/users/me/phone/*`; chỉ ghép UX đăng ký khi BE-86 xác nhận hợp đồng.
- Chuẩn hóa nhập số Việt Nam: khoảng trắng/ký tự phân tách, `0xxxxxxxxx`/`+84...` được normalize theo hợp đồng server; hiển thị số đích đã che phù hợp, chặn cú pháp sai trước request; không để người dùng đổi số đích âm thầm khi challenge đang hoạt động.
- Mô hình form rõ các trạng thái `idle → requesting → code_sent → verifying → verified` và `expired/limited/error`. Phân biệt “yêu cầu gửi mã thành công” với “đã gửi tới thiết bị” và “số đã xác minh”; server xác nhận cuối cùng bằng `phoneVerified=true`.
- Lưu đúng `challengeId` trả từ request, gắn OTP 6 chữ số và `currentPassword` tại verify. Bắt buộc chứng minh lại mật khẩu hiện tại theo backend; tài khoản Google chưa đặt mật khẩu phải có hướng dẫn phù hợp với luồng Identity đã duyệt, không tự bỏ bước xác minh này.
- Thêm resend/cooldown theo giới hạn server, làm rõ thời hạn mã, số lần thử và phản hồi chặn gửi. Chỉ hiển thị countdown nếu contract có deadline/remaining; đếm giây trên UI không được thay quyền quyết định của server. Khi xin challenge mới, vô hiệu hóa challenge cũ theo contract và reset mã nhập.
- Sau verify thành công, gọi lại `GET /api/users/me` để xác nhận persisted profile; khóa thay số đã verified theo hiện trạng `ProfileController`. Nếu fail/timeout, giữ form và cho kiểm tra trạng thái trước khi gửi lại, tránh phát trùng SMS vô ích.
- Chuẩn hóa lỗi validation/sai OTP/hết hạn/đã dùng/giới hạn gửi/thiếu mật khẩu hiện tại, mạng hoặc SMS provider unavailable; bảo toàn số đã nhập hợp lệ, chỉ clear OTP khi cần. 401 dẫn về đăng nhập theo session policy; 403 tài khoản/role và 409 số đã khóa không bị hiện thành lỗi chung.
- Hoàn thiện loading, keyboard numeric, paste mã, focus, disabled khi đang gửi/xác minh, `aria-live` thông báo thành công/thất bại, khung hẹp mobile, vi/en và Clucky brand. Không đưa OTP, mật khẩu, challenge vào URL, analytics, localStorage hay log; không giả lập đã nhận tin nhắn.
- Kiểm tra UI độc lập với SMS provider thật: môi trường chưa có provider phải phản ánh lỗi backend và ghi giới hạn nghiệm thu, không thay bằng OTP hard-code. Handoff các state, thông báo lỗi, endpoint, kịch bản và bằng chứng màn hình cho BE-86.
- **Phân biệt định danh:** Sprint 4 có **hai task UI-52**. File này chỉ nói về SMS OTP Customer; task còn lại là `S4-UI-52-ORDER-STATUS.md`. Không sửa hoặc ghi đè bản ghi kia khi đồng bộ Lark.

### 3. API

| API / hợp đồng | Quyền / yêu cầu |
| --- | --- |
| `GET /api/users/me` (đang có) | Customer đăng nhập; trả `phone` và `phoneVerified` theo Identity, `no-store`; backend xác thực actor từ phiên, không cho tra profile người khác. |
| `POST /api/users/me/phone/request` (đang có) | Customer ACTIVE; body `{phone}`; chuẩn hóa/validate số, trả `challengeId`; server sở hữu rate-limit, expiry, gửi SMS và kiểm tra số đã khóa. |
| `POST /api/users/me/phone/verify` (đang có) | Customer ACTIVE; body `{phone, otp, challengeId, currentPassword}`; OTP 6 chữ số và mật khẩu hiện tại; trả Profile có `phoneVerified` khi commit thành công. |
| `PUT /api/users/me` (đang có) | Customer cập nhật thông tin được phép; **không** dùng endpoint này để đổi/bind số trực tiếp; số khác bị backend chặn `OTP_REQUIRED` hoặc `ALREADY_VERIFIED`. |
| `POST /api/auth/register`, `/resend-otp`, `/verify-otp` (đang có) | Quy trình **OTP đăng ký/đăng nhập** riêng; không sử dụng challenge của endpoint này để xác nhận phone bind. Chi tiết kênh SMS đăng ký theo BE-86 phải được chốt trước khi nối UI. |
| Metadata `expiresAt`, `retryAfter`, `remainingAttempts` (hợp đồng cần chốt, chưa coi là có) | Chỉ hiển thị nếu API cung cấp và server bảo đảm; không tự sinh trường hoặc suy diễn chính sách bảo mật từ timer ở client. |

### 4. Tiêu chí hoàn thành

- Customer đăng nhập, chưa có số, nhập số VN hợp lệ và yêu cầu OTP → hiển thị số đích/challenge đang chờ; `phoneVerified` vẫn **false**.
- Nhập đúng OTP + mật khẩu hiện tại cho đúng `challengeId` → API xác nhận, đọc lại profile thấy số chuẩn hóa và `phoneVerified=true`; màn account hiển thị verified.
- Tải lại trang, mở tab mới hoặc đăng xuất/đăng nhập lại sau verify → trạng thái verified lấy từ server, không phụ thuộc React state.
- Thử cập nhật số khác bằng `PUT /api/users/me` khi đã verified → backend từ chối; form không hứa đã đổi hoặc mất số đã xác thực.
- Nhập số sai định dạng hoặc quốc gia chưa được BE-86 hỗ trợ → client nhắc sửa và backend vẫn trả validation; không gửi SMS.
- Nhập OTP sai/thiếu ký tự → hiển thị lỗi cụ thể; không đánh dấu verified và không gọi verify với 5 chữ số.
- Dùng OTP hết hạn, đã dùng hoặc challenge của số khác → bị từ chối; UI cho yêu cầu mã mới đúng điều kiện, không tự nối lại challenge cũ.
- Gửi lại nhiều lần/double click trong cooldown → không spam request; khi server trả 429, hiện giới hạn và thời điểm cho phép nếu có.
- Đổi số **chưa verified** sau khi đã yêu cầu mã → challenge cũ không được áp cho số mới; phải request challenge hợp lệ mới.
- Sai mật khẩu hiện tại dù OTP đúng → backend không bind số; form hiển thị lỗi chứng minh mật khẩu và không gọi refresh phiên vô hạn.
- Session hết hạn/khóa giữa hai bước → không xác thực bằng state cũ; chuyển theo chính sách session, không lộ profile riêng.
- Role Staff/Manager/Admin hoặc người không đăng nhập gọi phone bind Customer → 401/403 phù hợp, không sửa thông tin chủ thể khác.
- SMS provider trả 503/timeout sau request → không hiện “đã xác minh”; người dùng có cách thử lại/kiểm tra challenge mà không gửi lặp vô hạn.
- Verify phản hồi timeout nhưng backend đã commit → đọc lại profile xác nhận trạng thái, không hướng người dùng gửi mã lại ngay.
- Trên mobile và vi/en: nhập/paste 6 chữ số, điều khiển bằng bàn phím, loading/disabled/feedback đọc được và không vỡ layout.
- Kiểm tra URL, storage và log phía trình duyệt → không chứa OTP, mật khẩu hiện tại hoặc nội dung SMS; evidence không chụp mã thật.
- Bàn giao ảnh màn/trạng thái, danh sách mã lỗi, endpoint qua Gateway, kịch bản nghiệm thu có kết quả thực tế và ghi rõ giới hạn provider chưa sẵn sàng.

### 5. Điểm cần chốt

- BE-86 xác nhận “OTP SMS đăng ký” là xác thực tài khoản lúc register hay chỉ gắn số tại `/account`; quy định kênh, liên hệ với email OTP đăng ký và màn Customer tương ứng, không trộn hai challenge.
- Xác nhận hỗ trợ đầu số VN, chuẩn E.164, số nước ngoài, số trùng tài khoản khác, giới hạn sửa/khóa số verified và quy trình đổi số an toàn sau này.
- BE-86 cung cấp TTL, cooldown, remaining attempts, retry-after và thông điệp lỗi chuẩn; UI chỉ hiển thị metadata thực có, không áp giá trị phỏng đoán.
- Chốt cách Customer Google chưa có mật khẩu đáp ứng `currentPassword` để bind contact; phải có cơ chế step-up được Identity chấp thuận trước khi mở luồng này.
- Chốt môi trường/provider SMS, cách ghi nhận delivery failure và tài khoản kiểm thử an toàn để nghiệm thu mà không sử dụng OTP cố định.
