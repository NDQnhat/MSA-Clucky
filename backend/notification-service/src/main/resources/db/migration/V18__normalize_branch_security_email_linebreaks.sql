-- V17 has already been applied in existing installations: preserve its checksum.
-- Replace the literal backslash-n characters in the brief security templates.
UPDATE notification_service.mail_templates
SET body_template = replace(body_template, chr(92) || 'n', chr(10)),
    updated_at = now()
WHERE template_key IN (
    'BRANCH_ACTIVATE',
    'BRANCH_RESET',
    'BRANCH_RECOVERY',
    'BRANCH_ADMIN_RESET',
    'BRANCH_ADMIN_RECOVERY'
)
AND position(chr(92) || 'n' in body_template) > 0;
