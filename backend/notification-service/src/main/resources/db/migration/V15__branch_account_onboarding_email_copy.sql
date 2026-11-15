-- New branch accounts share a recovery mailbox but have separate credentials.
-- Keep these as security emails: the renderer adds the time-bound OTP and
-- target-account context, with no action button or embedded application URL.
UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Kích hoạt tài khoản {{login}}',
    body_template = 'Clucky đã tạo tài khoản chi nhánh với tên đăng nhập {{login}}.
Mỗi chi nhánh có hai tài khoản độc lập: quản lý (manager.<mã chi nhánh>) và tác nghiệp (staff.<mã chi nhánh>). Hai tài khoản có thể nhận mã tại cùng một email khôi phục nhưng phải kích hoạt và đặt mật khẩu riêng.

Cách kích hoạt tài khoản này:
1. Mở trang quản trị Clucky tại địa chỉ do đơn vị cung cấp và vào mục Đăng nhập.
2. Chọn Kích hoạt / khôi phục tài khoản. Nếu đã có email này, chuyển thẳng tới phần xác minh mã; không yêu cầu gửi lại mã.
3. Nhập đúng Mã challenge và mã xác minh 8 chữ số trong email này, sau đó tự đặt mật khẩu mới.
4. Quay về Đăng nhập và sử dụng tên đăng nhập {{login}} cùng mật khẩu vừa đặt.

Mã chỉ dùng cho tài khoản hiển thị bên dưới; không dùng mã của Manager cho Staff hoặc ngược lại. Nếu mã đã hết hạn, tại phần yêu cầu mã mới chọn Kích hoạt và nhập đúng tên đăng nhập của tài khoản. Mã trước đó sẽ hết hiệu lực.
Không chia sẻ mã xác minh hoặc mật khẩu.',
    updated_at = now()
WHERE template_key = 'BRANCH_ACTIVATE';

UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Đặt lại mật khẩu tài khoản {{login}}',
    body_template = 'Có yêu cầu đặt lại mật khẩu tài khoản {{login}}.
Tại trang quản trị Clucky, vào Đăng nhập rồi chọn Kích hoạt / khôi phục tài khoản. Sử dụng Mã challenge và mã xác minh 8 chữ số trong email này tại phần xác minh, tự đặt mật khẩu mới và đăng nhập lại.

Nếu mã hết hạn, chọn Đặt lại mật khẩu tại phần yêu cầu mã mới và nhập đúng tên đăng nhập. Mã của tài khoản này không thể dùng để đặt lại mật khẩu cho tài khoản khác, kể cả tài khoản dùng chung email khôi phục.
Nếu bạn không yêu cầu thao tác này, hãy bỏ qua email và liên hệ Admin nếu cần.',
    updated_at = now()
WHERE template_key = 'BRANCH_RESET';
