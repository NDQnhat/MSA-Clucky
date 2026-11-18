UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Kích hoạt {{login}}',
    body_template = 'Tài khoản {{login}} đã sẵn sàng.\nDùng Mã challenge và OTP trong email để đặt mật khẩu.',
    updated_at = now()
WHERE template_key = 'BRANCH_ACTIVATE';

UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Đặt lại mật khẩu {{login}}',
    body_template = 'Yêu cầu đặt lại mật khẩu cho {{login}}.\nDùng Mã challenge và OTP trong email để đặt mật khẩu mới.',
    updated_at = now()
WHERE template_key = 'BRANCH_RESET';

UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Xác minh email khôi phục',
    body_template = 'Yêu cầu đổi email khôi phục cho {{login}}.\n{{channelInstruction}}\nNhập OTP trong phiên quản trị đang mở.',
    updated_at = now()
WHERE template_key = 'BRANCH_RECOVERY';

UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Xác nhận đặt lại mật khẩu {{login}}',
    body_template = 'Admin đang xác nhận đặt lại mật khẩu cho {{login}}.\nNhập OTP trong phiên quản trị đang mở.',
    updated_at = now()
WHERE template_key = 'BRANCH_ADMIN_RESET';

UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Xác nhận đổi email khôi phục {{login}}',
    body_template = 'Admin đang xác nhận đổi email khôi phục cho {{login}}.\n{{channelInstruction}}\nNhập OTP trong phiên quản trị đang mở.',
    updated_at = now()
WHERE template_key = 'BRANCH_ADMIN_RECOVERY';
