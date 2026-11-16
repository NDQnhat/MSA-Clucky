## BE-90 — Khóa tài khoản thu hồi phiên, dọn HttpOnly cookie và trở về đăng nhập

**Phụ trách:** Chưa phân công · **Sprint:** 4 · **Ước lượng đề xuất:** 5 SP (9 giờ)
**Phụ thuộc:** Identity session/refresh/logout và revoke-all; Gateway kiểm JWT/revocation; `Browser_Sessions.md`; session boundary của Next.js Customer và React Admin/Manager/Staff.
**Ưu tiên đề xuất:** 1/5 · **Phạm vi:** Backend / Identity + Gateway và contract tích hợp hai frontend.

### 1. User Story

- **Là Admin**, khi khóa Customer hoặc tài khoản Manager/Staff, tôi muốn mọi phiên trước đó mất quyền gọi API, kể cả token/cookie vẫn còn trên thiết bị khác.
- **Là Customer/nhân sự bị hết phiên**, tôi muốn web thu hồi trạng thái đang đăng nhập và đưa đến login với thông báo dễ hiểu, không mắc ở màn “Có lỗi xảy ra, thử lại”.
- **Là người dùng còn phiên hợp lệ**, tôi muốn refresh tự phục hồi access JWT hết hạn và không bị đăng xuất oan khi mạng chập chờn, HTTP 503 hoặc một thao tác bị 403 vì thiếu quyền.
- **Là người quản trị hệ thống**, tôi muốn cookie HttpOnly được xóa ở đúng Path/client và dữ liệu phiên cũ không còn hiện trên tab khác hoặc sau hard refresh.

### 2. Công việc

- Rà đường khóa và revoke: `AdminUserService.changeStatus`, `BranchAccountService.lockAccount`, `RefreshTokenService.revokeAllForUser`, `SessionRevocationService`, `JwtIssuer`, `AccessTokenValidator`, `AuthService.session`. Mutations khóa ở Identity phải commit trạng thái LOCKED và thu hồi session trên **mọi thiết bị**; Gateway cùng business services từ chối token cũ, không chờ frontend xóa UI.
- Phân biệt lỗi máy chủ: `401 UNAUTHENTICATED` hoặc `TOKEN_REVOKED` là không còn phiên; `403 ACCOUNT_LOCKED` nghĩa tài khoản bị khóa; `403 FORBIDDEN` khác là thiếu quyền cho tác vụ cụ thể. Lỗi `503 DEPENDENCY_UNAVAILABLE`, `502/504`, network timeout không được chuyển thành logout theo suy đoán.
- Chuẩn hóa `GET /api/auth/session`, `POST /api/auth/refresh`, `POST /api/auth/logout`: auth/session chỉ lấy identity từ JWT + DB, không tin `userId/role` ở client; refresh hợp lệ được phép xoay cookie và retry request một lần; refresh expired/revoked/locked bắt buộc clear cookie. Logout an toàn/idempotent dù refresh cookie đã mất hoặc bị thu hồi.
- Dùng `X-Clucky-Client: customer` cho Next.js và `admin` cho React; prefix cookie lần lượt `clucky_customer_*`, `clucky_admin_*`. Identity set/clear cookie **HttpOnly, SameSite=Lax, Secure theo môi trường**; access `Path=/api`, refresh `Path=/api/auth`, legacy `clucky_refresh_token` cũng xóa tại `/api/auth`. `Max-Age=0` phải khớp tên/path và origin thực; JavaScript không thể xóa cookie HttpOnly trực tiếp.
- Rà Gateway: bảo đảm `AccessTokenValidator` kiểm signature, TTL, session id/revocation; 401/403 có CORS/credential cho origin hợp lệ. Dọn cookie ở Identity khi request đến `/session`/refresh; trường hợp Gateway từ chối trước Identity, frontend vẫn phải gọi logout/cleanup để nhận `Set-Cookie` từ backend.
- Trong `frontend/nextjs/lib/session.ts` và `frontend/react/src/lib/session.ts`, giữ **single-flight refresh** trong tab và phối hợp nhiều tab qua Web Locks/storage event theo `Browser_Sessions.md`. Sau xác thực thất bại cuối cùng, có một đường `endSession/invalidate`: xóa user state/cache riêng tư, dừng refresh/retry, broadcast đến tab cùng app, gọi server dọn cookie.
- UI Customer `session-boundary` và portal `ProtectedRoute/AuthContext` điều hướng trang có auth đến `/login?redirect=...` hoặc login tương ứng khi session chắc chắn vô hiệu. Không giữ màn lỗi chung có retry vô hạn hoặc chớp dữ liệu Customer/nhân sự trước. Khi login trở lại, chỉ phục hồi trang redirect hợp lệ của ứng dụng.
- Không refresh trên 403 thiếu quyền, OTP/password lỗi hoặc POST login sai; lỗi mạng/5xx hiển thị khả năng thử lại và giữ phiên ở trạng thái chưa xác định. Cần ngăn yêu cầu cũ hoàn thành muộn ghi lại state của account đã logout hoặc bị đổi phiên.
- Ở tab đang mở, xử lý ngay 401/`ACCOUNT_LOCKED` từ API; kiểm tra session khi focus/visibility trở lại và chu kỳ nhẹ khi tab hiển thị (source đang có khoảng 15 giây). Nếu không có request/chỉ tab ẩn, chuyển login có thể chậm đến lần check kế tiếp; không tuyên bố push thời gian thực.
- Khi đăng xuất Customer, không xóa nhầm cookie admin/portal ở cùng host; khi Admin khóa một account của quán, account còn lại chỉ mất phiên nếu có quy tắc recovery tác động cả cặp. Tách khách và nhân sự, tab và thiết bị theo đúng identity.
- Rà active lease của các nghiệp vụ dựa vào phiên (loyalty live QR, cart/order, các request chờ). Khi invalidation, không để stale proof/token riêng của customer được tái dùng; backend vẫn phải guard owner/role/store trên mọi endpoint.
- Bổ sung kiểm chứng header `Set-Cookie`, response error catalog, race refresh/lock/logout, nhiều tab/thiết bị và đoạn hướng dẫn tái hiện lỗi. Nếu thay đổi cấu hình/migration phải dùng đường Config Server/Flyway mới, không chèn secret hoặc sửa migrations đã áp.

### 3. API

| API / trạng thái | Quyền, dữ liệu và quy tắc |
| --- | --- |
| **Hiện có** `PUT /api/users/{id}/lock`, `PUT /api/users/customers/{id}/lock` | ADMIN theo `AdminUserController` và RBAC; khóa đúng target, thu hồi mọi session, audit; không cho tự khóa user ngoài quyền. |
| **Hiện có** `PUT /api/auth/branch/accounts/{id}/lock?locked=true/false` | ADMIN; lock/unlock account chi nhánh đích, revoke phiên theo `BranchAccountService`. |
| **Hiện có** `GET /api/auth/session` | Access session từ cookie/JWT; dùng `X-Clucky-Client` để xác định nhóm client; account LOCKED/đã revoke → lỗi auth và xóa cookie khi request tới Identity. |
| **Hiện có** `POST /api/auth/refresh` | Cookie refresh HttpOnly của client; rotate khi còn hợp lệ; `401` hoặc `ACCOUNT_LOCKED` → `Set-Cookie` Max-Age=0 đúng cặp. |
| **Hiện có** `POST /api/auth/logout` | Cookie refresh hiện tại; thu hồi sid của thiết bị đang logout, clear cookie cả khi token không hợp lệ; gọi nhiều lần an toàn. |
| **Hiện có** `POST /api/auth/login`, `/google`, `/google/complete`, `/verify-otp` | Các cách cấp phiên hiện hành; chỉ khi account ACTIVE và role tương thích client, không mở khóa bằng login/OTP sai. |
| **Hợp đồng hiện có** Gateway `/api/**` JWT validation | Gateway đọc access cookie, kiểm issuer/audience/expiry/revocation và chuyển identity tin cậy tới service; 401 tại Gateway cần được client nhận biết để cleanup. |
| **Hợp đồng hai frontend hiện có** session library + auth boundary | Request kèm credentials, refresh single-flight, 401/ACCOUNT_LOCKED endSession và điều hướng, 403 FORBIDDEN/mạng lỗi giữ hành vi riêng. Đây là logic ứng dụng, không có endpoint dọn cookie mới. |
| **Không đề xuất** mở route public “unlock/logout all” | Quyền khóa, revoke và cleanup dùng API hiện hành; nếu cần instant push phải thiết kế kênh SSE/WebSocket riêng. |

### 4. Tiêu chí hoàn thành

- Admin khóa Customer đang mở `/account` → request/check phiên tiếp theo bị từ chối, UI xóa state và về login.
- Admin khóa Customer đang `/orders` hoặc `/checkout` → không thấy dữ liệu user cũ sau redirect/hard reload, Order API vẫn guard ownership.
- Khóa Staff/Manager đang đăng nhập trên hai thiết bị → cả hai không thể gọi mutation mới bằng cookie/JWT cũ; không cần chờ frontend xử lý.
- STAFF quán A thử gọi API quán B và nhận `403 FORBIDDEN` → session quán A còn hợp lệ, UI chỉ báo thiếu quyền.
- Tài khoản bị `ACCOUNT_LOCKED` 403 → client nhận đúng code, kết thúc phiên; không coi là 403 nghiệp vụ thông thường.
- Access JWT hết TTL nhưng refresh còn hợp lệ → refresh một lần và retry request hợp lệ; không logout vì 401 ban đầu.
- Refresh token hết hạn/thu hồi/sai chữ ký/replay → `401`, clear cookie server, dừng vòng lặp và về login.
- JWT bị revoke nhưng Gateway từ chối trước Identity → frontend gọi cleanup logout để server phát xóa HttpOnly cookie.
- Kiểm `Set-Cookie` Customer: `clucky_customer_access` Path `/api` và `clucky_customer_refresh` Path `/api/auth` đều Max-Age=0, thuộc tính Secure/SameSite khớp môi trường.
- Kiểm `Set-Cookie` portal: `clucky_admin_access` Path `/api` và `clucky_admin_refresh` Path `/api/auth` đều được clear; legacy `clucky_refresh_token` cũng clear, không sót cookie.
- Logout Customer rồi xem phiên React Admin/Manager trong cùng trình duyệt → cặp cookie/session portal không bị xóa chéo; chiều ngược lại tương tự.
- Hai tab Customer cùng refresh khi token hết hạn → một luồng xoay token theo cơ chế phối hợp, không replay/ghi lại user cũ.
- Tab A logout/khóa, tab B còn đang hiển thị → nhận broadcast hoặc session check và về login; không flash dữ liệu riêng.
- Tab không thao tác nhưng còn hiển thị → nhận khóa sau chu kỳ kiểm tra định kỳ; tab ẩn kiểm lại khi focus/visibility quay về.
- Refresh/logout đang chạy đồng thời với lock → lock thắng về quyền; không hồi sinh phiên sau response đến muộn.
- Lỗi mạng tạm thời, 503/504 hoặc backend restart → UI hiển thị retry, không tự kết luận ACCOUNT_LOCKED, không xóa state vĩnh viễn.
- Login sai mật khẩu, OTP sai, hoặc session của role không phù hợp client → lỗi auth thích hợp, không phát cookie quyền trái role.
- Logout khi cookie đã bị xóa hoặc không đọc được → vẫn trả thành công an toàn và trả header xóa cookie; không tạo vòng lặp.
- Khóa account gắn loyalty QR/live session → proof/lease không thể tiếp tục được dùng để lấy dữ liệu/điểm dưới phiên đã vô hiệu.
- Bàn giao bảng trạng thái HTTP/code→hành vi, evidence header cookie, phiên nhiều tab/thiết bị và kết quả kiểm chứng API/UX thực tế (Docker healthy không thay thế nghiệm thu).

### 5. Điểm cần chốt

- Chốt thời gian UX tối đa mong muốn giữa **khóa account** và **điều hướng login** ở tab không phát request: cơ chế hiện tại focus/interval khoảng 15 giây, yêu cầu tức thời cần push riêng.
- Chốt mã lỗi Gateway cho JWT expired/revoked khi Gateway chặn trước Identity, cách client nhận `401` và bắt đầu cleanup mà không refresh sai.
- Chốt redirect sau logout/khóa cho Customer và portal, giữ đường dẫn được phép nhưng không tạo open redirect hay hiển thị private route.
- Chốt phạm vi revoke khi Admin khóa một shared Staff/Manager, hoặc đổi recovery email chung: account nào cần mất phiên, khi nào phát broadcast cho các thiết bị.
- Chốt chính sách dùng Web Locks nếu trình duyệt không hỗ trợ, TTL/poll interval và yêu cầu e2e cho tab/visibility ở môi trường production HTTPS.
