-- The renderer already supplies the Clucky header, OTP, expiry, account and challenge details.
-- Keep the template itself to one short introduction. Only BRANCH_ACTIVATE changes.
UPDATE notification_service.mail_templates
SET subject_template = 'Clucky — Kích hoạt tài khoản {{login}}',
    body_template = 'Tài khoản {{login}} ({{roleLabel}}) đã sẵn sàng.
Dùng mã xác minh bên dưới để đặt mật khẩu.',
    updated_at = now()
WHERE template_key = 'BRANCH_ACTIVATE';
