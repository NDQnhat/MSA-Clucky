SET search_path TO notification_service, public;

ALTER TABLE notification_service.notifications
    ALTER COLUMN title TYPE JSONB USING jsonb_build_object('vi', title, 'en', title),
    ALTER COLUMN content TYPE JSONB USING jsonb_build_object('vi', content, 'en', content);

ALTER TABLE notification_service.admin_notifications
    ALTER COLUMN title TYPE JSONB USING jsonb_build_object('vi', title, 'en', title),
    ALTER COLUMN content TYPE JSONB USING jsonb_build_object('vi', content, 'en', content);

ALTER TABLE notification_service.branch_notification_intents
    ALTER COLUMN title TYPE JSONB USING jsonb_build_object('vi', title, 'en', title),
    ALTER COLUMN content TYPE JSONB USING jsonb_build_object('vi', content, 'en', content);

UPDATE notification_service.notifications
SET title = jsonb_build_object('vi', 'Đơn hàng đã hoàn thành', 'en', 'Order Completed'),
    content = jsonb_build_object('vi', 'Đơn CLK-DEMO-0001 đã hoàn thành. Cảm ơn bạn đã đặt món tại Clucky!', 'en', 'Order #CLK-DEMO-0001 is completed. Thank you for ordering with Clucky!')
WHERE id = 1 AND title->>'vi' = title->>'en';

UPDATE notification_service.notifications
SET title = jsonb_build_object('vi', 'Đơn hàng đã được xác nhận', 'en', 'Order Confirmed'),
    content = jsonb_build_object('vi', 'Đơn CLK-DEMO-0002 đang được chi nhánh chuẩn bị.', 'en', 'Order #CLK-DEMO-0002 is being prepared at the branch.')
WHERE id = 2 AND title->>'vi' = title->>'en';

ALTER TABLE notification_service.mail_templates
    ADD COLUMN IF NOT EXISTS subject_i18n JSONB,
    ADD COLUMN IF NOT EXISTS body_i18n JSONB,
    ADD COLUMN IF NOT EXISTS html_i18n JSONB;

ALTER TABLE notification_service.mail_template_revisions
    ADD COLUMN IF NOT EXISTS subject_i18n JSONB,
    ADD COLUMN IF NOT EXISTS body_i18n JSONB,
    ADD COLUMN IF NOT EXISTS html_i18n JSONB;

UPDATE notification_service.mail_templates
SET subject_i18n = jsonb_build_object('vi', subject_template, 'en', subject_template),
    body_i18n = jsonb_build_object('vi', body_template, 'en', body_template),
    html_i18n = CASE WHEN html_template IS NOT NULL THEN jsonb_build_object('vi', html_template, 'en', html_template) ELSE NULL END
WHERE subject_i18n IS NULL OR body_i18n IS NULL;

UPDATE notification_service.mail_template_revisions
SET subject_i18n = jsonb_build_object('vi', subject_template, 'en', subject_template),
    body_i18n = jsonb_build_object('vi', body_template, 'en', body_template),
    html_i18n = CASE WHEN html_template IS NOT NULL THEN jsonb_build_object('vi', html_template, 'en', html_template) ELSE NULL END
WHERE subject_i18n IS NULL OR body_i18n IS NULL;

-- Provide proper English copy for active mail templates
UPDATE notification_service.mail_templates
SET subject_i18n = jsonb_set(subject_i18n, '{en}', to_jsonb(CASE template_key
        WHEN 'register-otp' THEN 'Clucky — Verify your email address'
        WHEN 'reset-password-otp' THEN 'Clucky — Password reset verification code'
        WHEN 'ORDER_CREATED' THEN 'Clucky — Order {{orderCode}} received'
        WHEN 'ORDER_CONFIRMED' THEN 'Clucky — Order {{orderCode}} confirmed'
        WHEN 'ORDER_PREPARING' THEN 'Clucky — Preparing order {{orderCode}}'
        WHEN 'ORDER_READY' THEN 'Clucky — Order {{orderCode}} is ready'
        WHEN 'ORDER_COMPLETED' THEN 'Clucky — Thank you! Order {{orderCode}} completed'
        WHEN 'ORDER_CANCELLED' THEN 'Clucky — Order {{orderCode}} cancelled'
        WHEN 'PAYMENT_CONFIRMED' THEN 'Clucky — Payment received for order {{orderCode}}'
        WHEN 'PAYMENT_FAILED' THEN 'Clucky — Payment update for order {{orderCode}}'
        WHEN 'REFUND_APPROVED' THEN 'Clucky — Refund request received for order {{orderCode}}'
        WHEN 'REFUND_REJECTED' THEN 'Clucky — Refund request update for order {{orderCode}}'
        WHEN 'REFUND_WAITING_BANK_DETAILS' THEN 'Clucky — Bank details needed for order {{orderCode}}'
        WHEN 'REFUND_PROCESSED' THEN 'Clucky — Refund completed for order {{orderCode}}'
        WHEN 'BRANCH_ACTIVATE' THEN 'Clucky — Activate account {{login}}'
        WHEN 'BRANCH_RESET' THEN 'Clucky — Reset staff account password'
        WHEN 'BRANCH_RECOVERY' THEN 'Clucky — Verify branch recovery email'
        WHEN 'BRANCH_ADMIN_RESET' THEN 'Clucky — Confirm admin password reset'
        WHEN 'BRANCH_ADMIN_RECOVERY' THEN 'Clucky — Confirm recovery email change'
        ELSE subject_template
    END)),
    body_i18n = jsonb_set(body_i18n, '{en}', to_jsonb(CASE template_key
        WHEN 'register-otp' THEN E'Welcome to Clucky, you are just one step away.\nEnter the verification code below on the registration screen to finish setting up your account.'
        WHEN 'reset-password-otp' THEN E'You requested a password reset.\nEnter the verification code below in your active recovery session to continue.'
        WHEN 'ORDER_CREATED' THEN E'Hello {{customerName}}, Clucky has received your order.\nYour order is awaiting payment. Open order details to view payment instructions and deadlines.'
        WHEN 'ORDER_CONFIRMED' THEN E'Hello {{customerName}}, the branch has confirmed your order.\nClucky will notify you as soon as your food is ready.'
        WHEN 'ORDER_PREPARING' THEN E'Hello {{customerName}}, the kitchen is preparing your order.\nClucky will let you know when it is ready.'
        WHEN 'ORDER_READY' THEN E'Hello {{customerName}}, your order has been prepared.\n{{readyInstruction}}'
        WHEN 'ORDER_COMPLETED' THEN E'Hello {{customerName}}, your order is complete.\nThank you for dining with Clucky. Open order details to review your order.'
        WHEN 'ORDER_CANCELLED' THEN E'Hello {{customerName}}, your order has been cancelled.\nReason: {{reason}}'
        WHEN 'PAYMENT_CONFIRMED' THEN E'Clucky has confirmed your payment.\nYou can review transaction details and order progress inside the app.'
        WHEN 'PAYMENT_FAILED' THEN E'Your payment could not be confirmed.\nPlease open order details to check the status and next steps.'
        WHEN 'REFUND_APPROVED' THEN E'Your refund request has been approved for processing.\nClucky will update you when the transfer finishes or if more details are needed.'
        WHEN 'REFUND_REJECTED' THEN E'Your refund request was declined.\nPlease sign in and open the request to see the reason and guidance.'
        WHEN 'REFUND_WAITING_BANK_DETAILS' THEN E'Your refund requires bank account verification.\nPlease sign in and update your bank details directly in the refund request.'
        WHEN 'REFUND_PROCESSED' THEN E'Clucky has completed the refund for your order.\nYou can open the request to review and verify transaction details.'
        WHEN 'BRANCH_ACTIVATE' THEN E'Account {{login}} ({{roleLabel}}) is ready.\nUse the verification code below to set your password.'
        WHEN 'BRANCH_RESET' THEN E'You requested a password reset for a staff/admin account.\nEnter the verification code below in the matching recovery session.'
        WHEN 'BRANCH_RECOVERY' THEN E'There is a request to update the recovery email for the account below.\n{{channelInstruction}}\nEnter this code only in your active admin session.'
        WHEN 'BRANCH_ADMIN_RESET' THEN E'You are confirming an Admin password reset for the account below.\nEnter this code only in the admin session you initiated.'
        WHEN 'BRANCH_ADMIN_RECOVERY' THEN E'You are confirming an Admin recovery email change for the account below.\n{{channelInstruction}}\nEnter this code only in the admin session you initiated.'
        ELSE body_template
    END))
WHERE subject_i18n->>'en' = subject_i18n->>'vi';

UPDATE notification_service.mail_template_revisions r
SET subject_i18n = t.subject_i18n,
    body_i18n = t.body_i18n
FROM notification_service.mail_templates t
WHERE r.template_key = t.template_key
  AND r.id = t.published_revision_id
  AND r.subject_i18n->>'en' = r.subject_i18n->>'vi';
