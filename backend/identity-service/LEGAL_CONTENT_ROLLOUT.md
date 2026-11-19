# Nội dung pháp lý và xác nhận đăng ký

## Nguyên tắc

Tài liệu Điều khoản dịch vụ (`TERMS_OF_SERVICE`) và Chính sách quyền riêng tư (`PRIVACY_POLICY`) do Admin quản lý độc lập. **Không có biến cấu hình bật/tắt consent hoặc bước rollout thủ công.** Hệ thống tự xác định điều kiện đăng ký từ các bản **đã xuất bản** trong database:

| Trạng thái công bố | Xác nhận lúc đăng ký |
|---|---|
| Cả hai chưa xuất bản | Không cần `consent`; không ghi `legal_consents` |
| Chỉ Điều khoản được xuất bản | `{ "accepted": true, "termsVersion": n, "privacyVersion": null }` |
| Chỉ Chính sách được xuất bản | `{ "accepted": true, "termsVersion": null, "privacyVersion": n }` |
| Cả hai đã xuất bản | `{ "accepted": true, "termsVersion": n, "privacyVersion": m }` |

Client không được tạo phiên bản cho tài liệu chưa được xuất bản. Phiên bản cũ, giá trị giả mạo, thiếu checkbox hoặc thiếu phiên bản của tài liệu được xuất bản sẽ bị từ chối. Trạng thái từ public API chỉ giúp hiển thị UI; **Identity luôn kiểm tra lại trong transaction của chính lần đăng ký**. Lỗi database/API không được diễn giải thành “chưa có tài liệu”.

## Admin vận hành

1. Admin truy cập `/admin/legal`, soạn thảo tiếng Việt và tiếng Anh cho từng tài liệu.
2. Dùng **Lưu nháp → Xem trước → Xuất bản**, chỉ xuất bản sau khi chủ sản phẩm/pháp chế duyệt. Template là khung biên tập, không phải văn bản pháp lý đã được phê duyệt.
3. Có thể gỡ xuất bản từng tài liệu mà không mất lịch sử: `POST /api/legal/admin/{type}/unpublish`, JSON `{"version": <publishedVersion hiện tại>}`, phản hồi `204 No Content`. Backend kiểm tra phiên bản đang publish dưới row lock; nếu đã thay đổi/gỡ sẽ trả `409`, tránh thao tác từ tab Admin cũ.
4. Gỡ xuất bản không xóa `legal_revisions`, không sửa `legal_consents` đã ghi trước đó. Xuất bản lại cùng tài liệu yêu cầu tạo bản nháp mới hoặc restore lịch sử thành bản nháp rồi publish.

Toàn bộ route `/api/legal/admin/**` yêu cầu quyền ADMIN. Các thao tác lưu nháp, restore, publish, unpublish ghi audit. HTML được lọc phía server bằng Jsoup; chỉ cho phép định dạng và liên kết HTTP(S), không thực thi script.

## Public API và đăng ký

- `GET /api/legal/public/status` trả `consentRequired` (true nếu **ít nhất một** tài liệu đã được xuất bản), `termsPublished`, `privacyPublished` và `documentsPublished` (true khi **cả hai** đã xuất bản, tương thích client cũ).
- `GET /api/legal/public/TERMS_OF_SERVICE`, `GET /api/legal/public/PRIVACY_POLICY`: chỉ trả revision đang publish; chưa publish trả 404.
- Khi đăng ký điện thoại (`/api/auth/register`) hoặc tạo tài khoản Google mới (`/api/auth/google/complete`), backend khóa **hai** hàng `legal_documents` bằng `SELECT ... FOR SHARE OF d` (kể cả chưa có bản publish). Giao dịch publish/unpublish giữ `FOR UPDATE OF d`, nên không thể thay đổi điều kiện consent trong lúc đăng ký.
- Dữ liệu `legal_consents` được ghi theo revision ID thực của tài liệu được chấp thuận, ID còn lại có thể NULL. Timestamp `accepted_at` lấy từ PostgreSQL; audit tiếp tục hoạt động. Khi cả hai chưa công bố, không tạo bản ghi chấp thuận giả.
- Đăng nhập Google cho **tài khoản đã có** không cần consent mới. Token Google pending chỉ xóa khỏi Redis sau khi giao dịch đăng ký thành công.
- Nếu public status không truy cập được, UI phải hiển thị lỗi và không tự coi như “chưa publish”. Backend kiểm tra độc lập; lỗi truy vấn DB làm đăng ký thất bại.

## Migration, triển khai, xác minh

Identity migrations `V14__account_device_sessions.sql`, `V15__legal_documents_and_consents.sql`, `V16__allow_partial_legal_consent.sql` chạy theo thứ tự tăng dần. **V16 không sửa các migration đã chạy**: chỉ bỏ NOT NULL ở hai khóa ngoại revision và thêm CHECK bắt buộc ít nhất một revision ID khác NULL. Các bản ghi đã tồn tại (cả hai ID đều NOT NULL) giữ nguyên.

CI/CD production không còn gate rollout pháp lý; gate bỏ qua release chỉ thay tài liệu ở `deploy/release_gate.py` vẫn giữ nguyên. Nếu GitHub Secret `BACKEND_ENV_JSON` còn chứa khóa cấu hình pháp lý cũ, `prepare.py` bỏ qua hoàn toàn để không chặn bản cập nhật; biến đó không được truyền vào runtime và không ảnh hưởng đến logic xác nhận.

Trước production, kiểm tra trên staging: migration V14→V15→V16 với PostgreSQL thật; đăng ký phone/Google với 0/1/2 tài liệu; gỡ xuất bản rồi đăng ký; phiên bản stale/synthetic; race giữa publish/unpublish và đăng ký; các endpoint public, Admin RBAC và XSS. Chỉ publish nội dung đã được chủ sản phẩm/pháp chế phê duyệt. Không tự động publish template.