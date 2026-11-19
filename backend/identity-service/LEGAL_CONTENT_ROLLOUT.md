# PR4 — Nội dung pháp lý và ghi nhận chấp thuận

## Phụ thuộc và thứ tự triển khai

1. **Merge PR5 #85 trước**: identity-service đã dùng Flyway V14__account_device_sessions.sql.
2. Merge PR4 sau PR5; migration pháp lý là V15__legal_documents_and_consents.sql, tạo legal_documents, legal_revisions, legal_consents trong identity_service. Không sửa migration đã chạy.
3. Không tự publish văn bản mẫu: nội dung gợi ý trong React Admin là **khung soạn thảo, chưa được kiểm duyệt pháp lý**. Chỉ người chịu trách nhiệm pháp chế/chủ sản phẩm mới quyết định nội dung chính thức.

## Rollout không gián đoạn đăng ký

Triển khai theo giai đoạn, không dùng PR này để tự thay đổi production:

1. Triển khai **identity-service + API Gateway + Config Server + React Admin** với LEGAL_CONSENT_REQUIRED=false (chỉ trong giai đoạn chuyển tiếp), giữ **Next.js cũ** đang hoạt động. Backend cho phép các request đăng ký cũ chưa có trường consent; **không tạo bản ghi chấp thuận giả**.
2. ADMIN mở /admin/legal, chỉnh sửa đầy đủ **tiếng Việt và tiếng Anh** cho **Điều khoản dịch vụ** và **Chính sách quyền riêng tư**; lần lượt **Lưu bản nháp mới → Xem trước → Xuất bản**. Kiểm tra hai GET:
   - GET /api/legal/public/TERMS_OF_SERVICE
   - GET /api/legal/public/PRIVACY_POLICY
   Phải trả 200, kèm version và HTML đúng bản được duyệt.
3. Triển khai **Next.js mới**: /terms-of-service, /privacy-policy, checkbox đăng ký điện thoại và Google mới đều liên kết tới hai tài liệu.
4. Chuyển LEGAL_CONSENT_REQUIRED=true (**mặc định của mã và cấu hình**) trên identity-service. Thực hiện smoke test đăng ký điện thoại/Google không tick (400), tick đúng bản mới (lưu thành công), bản cũ (409), xác nhận legal_consents chứa đúng IDs phiên bản và thời điểm server; tài khoản Google có sẵn vẫn đăng nhập không cần chấp thuận lại.

**Cảnh báo:** Nếu bật chế độ bắt buộc trước khi xuất bản đủ **cả hai** văn bản, đăng ký mới sẽ bị chặn 409 (fieldErrors.consent=NOT_PUBLISHED). Đây là hành vi fail-closed, không có bản điều khoản giả được tự xuất bản.

## Thiết kế và bảo mật

- **ADMIN-only**: /api/legal/admin/{type}, /{type}/history, /{type}/revisions/{version}, /{type}/draft, /{type}/publish, /{type}/restore/{version}. Mỗi lần lưu nháp tạo một phiên bản mới bất biến; publish chỉ nhận phiên bản draft hiện hành; khôi phục sao chép phiên bản cũ thành **draft phiên bản mới**, không sửa lịch sử.
- **Public read-only**: chỉ GET /api/legal/public/{type} cho phiên bản đã publish; không trả thông tin admin người tạo hoặc bản nháp.
- Các request tạo tài khoản nhận consent: accepted=true, termsVersion, privacyVersion. Identity đối chiếu **hai published revisions** với SELECT FOR SHARE trong cùng transaction đăng ký; thay đổi publish đợi commit. Bản ghi legal_consents gắn tới cả hai revision IDs bất biến, với accepted_at DEFAULT now() do PostgreSQL sinh và source (PHONE_REGISTER/GOOGLE_COMPLETE). OTP điện thoại xác minh sau khi chấp thuận đã được ghi nhận. Google cũ dùng /google đăng nhập trực tiếp, **không bị ràng buộc chấp thuận mới**.
- HTML đầu vào được **server** lọc bằng JSoup safelist, chỉ giữ nội dung văn bản/định dạng cơ bản và liên kết HTTP(S); event handlers, script, iframe, ảnh và javascript: bị loại. Preview phía Admin dùng iframe sandbox và CSP hạn chế; public Next chỉ render HTML đã lưu qua sanitizer.
- Dữ liệu HTML cả hai ngôn ngữ tối đa 100 KB/khối; lịch sử API giới hạn 100 revisions gần nhất. Không có API xóa revisions hoặc chấp thuận.

## Xác minh và giới hạn

Các bài test unit kiểm tra sanitization/XSS, consent bắt buộc, bản đã xuất bản, stale version, rollout flag, khả năng tái sử dụng pending Google token khi consent lỗi. Cần tiếp tục xác minh tích hợp trên staging với PostgreSQL thực, Flyway V14→V15, Redis, API Gateway, thiết bị thực và hai ứng dụng trước khi production.
