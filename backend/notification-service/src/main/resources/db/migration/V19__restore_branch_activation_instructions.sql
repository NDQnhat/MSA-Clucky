-- V17 shortened the activation email and removed the branch-specific setup steps.
-- Restore the instructions while retaining the current challenge and OTP renderer.
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
