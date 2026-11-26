package com.clucky.notificationservice.service;

import com.clucky.notificationservice.config.LocaleResolverHelper;
import com.clucky.notificationservice.dto.LocalizedText;
import com.clucky.notificationservice.entity.MailTemplate;
import com.clucky.notificationservice.repository.MailTemplateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MailTemplateManagementService {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    // Keep these contracts aligned with NotificationConsumer.resolveNotificationSpec.
    // A variable supplied only for one event must not be published in another template:
    // otherwise the renderer fails the delivery with INVALID_TEMPLATE at runtime.
    private static final Set<String> COMMON = Set.of("orderCode", "customerName");
    private static final Map<String, Set<String>> TRANSACTION_VARIABLES = Map.ofEntries(
            Map.entry("ORDER_CREATED", COMMON),
            Map.entry("ORDER_CONFIRMED", COMMON),
            Map.entry("ORDER_PREPARING", COMMON),
            Map.entry("ORDER_READY", Set.of("orderCode", "customerName", "readyInstruction")),
            Map.entry("ORDER_COMPLETED", COMMON),
            Map.entry("ORDER_CANCELLED", Set.of("orderCode", "customerName", "reason")),
            Map.entry("PAYMENT_CONFIRMED", Set.of("orderCode", "customerName", "amount")),
            Map.entry("PAYMENT_FAILED", Set.of("orderCode", "customerName", "paymentExplanation")),
            Map.entry("REFUND_APPROVED", COMMON),
            Map.entry("REFUND_REJECTED", Set.of("orderCode", "customerName", "reason")),
            Map.entry("REFUND_WAITING_BANK_DETAILS", Set.of("orderCode", "customerName", "reason")),
            Map.entry("REFUND_PROCESSED", Set.of("orderCode", "customerName", "amount")));
    private static final Set<String> BRANCH_VARIABLES = Set.of("channelInstruction", "login", "roleLabel");
    private static final Set<String> DOCUMENT_VARIABLES = Set.of(
            "subject", "title", "eyebrow", "content", "details", "action", "notice", "footer");
    private final JdbcTemplate jdbc;
    private final MailTemplateRepository templates;
    private final EmailTemplateRenderer renderer;

    public MailTemplateManagementService(JdbcTemplate jdbc, MailTemplateRepository templates, EmailTemplateRenderer renderer) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.renderer = renderer;
    }

    public record Summary(String key, String subject, LocalizedText subjectI18n, boolean active, Instant updatedAt,
            Long publishedRevisionId, int latestRevision) {
        public Summary(String key, String subject, boolean active, Instant updatedAt,
                Long publishedRevisionId, int latestRevision) {
            this(key, subject, LocalizedText.ofSingle(subject), active, updatedAt, publishedRevisionId, latestRevision);
        }
    }
    public record Revision(long id, int revision, String subject, LocalizedText subjectI18n,
            String text, LocalizedText textI18n, String html, LocalizedText htmlI18n,
            long actorId, Instant createdAt, Instant publishedAt) {
        public Revision(long id, int revision, String subject, String text, String html,
                long actorId, Instant createdAt, Instant publishedAt) {
            this(id, revision, subject, LocalizedText.ofSingle(subject),
                    text, LocalizedText.ofSingle(text),
                    html, html == null ? null : LocalizedText.ofSingle(html),
                    actorId, createdAt, publishedAt);
        }
    }
    public record Detail(Summary template, Revision published, Revision draft, List<Revision> history) {}
    public record Preview(String subject, String text, String html) {}
    public record Layout(String html) {}

    @Transactional(readOnly = true)
    public Layout layout() {
        return new Layout(renderer.defaultLayout());
    }

    @Transactional(readOnly = true)
    public List<Summary> list() {
        String lang = LocaleResolverHelper.currentRequestLang();
        return jdbc.query("""
                SELECT t.template_key,t.subject_template,t.subject_i18n,t.is_active,t.updated_at,t.published_revision_id,
                       COALESCE((SELECT max(r.revision_number) FROM notification_service.mail_template_revisions r
                                 WHERE r.template_key=t.template_key),0) AS latest_revision
                FROM notification_service.mail_templates t
                WHERE t.is_active=TRUE ORDER BY t.template_key
                """, (rs, index) -> {
            LocalizedText subjectI18n = LocalizedText.fromDbValue(rs.getString("subject_i18n"));
            if (subjectI18n == null) {
                subjectI18n = LocalizedText.ofSingle(rs.getString("subject_template"));
            }
            return new Summary(rs.getString("template_key"), LocalizedText.resolve(subjectI18n, lang), subjectI18n,
                    rs.getBoolean("is_active"), rs.getTimestamp("updated_at").toInstant(),
                    rs.getObject("published_revision_id", Long.class), rs.getInt("latest_revision"));
        });
    }

    @Transactional(readOnly = true)
    public Detail get(String key) {
        String lang = LocaleResolverHelper.currentRequestLang();
        Summary summary = list().stream().filter(row -> key.equals(row.key())).findFirst().orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown active email template"));
        List<Revision> revisions = jdbc.query("""
                SELECT * FROM notification_service.mail_template_revisions
                WHERE template_key=? ORDER BY revision_number DESC LIMIT 60
                """, (rs, index) -> mapRevision(rs, lang), key);
        Revision published = revisions.stream().filter(row -> summary.publishedRevisionId() != null
                && summary.publishedRevisionId() == row.id()).findFirst().orElse(null);
        Revision draft = revisions.stream().filter(row -> row.publishedAt() == null
                && (published == null || row.revision() > published.revision())).findFirst().orElse(null);
        return new Detail(summary, published, draft, revisions);
    }

    @Transactional
    public Detail saveDraft(String key, int expectedRevision, String subject, String text, String html, long actorId) {
        return saveDraft(key, expectedRevision, subject, null, text, null, html, null, actorId);
    }

    @Transactional
    public Detail saveDraft(String key, int expectedRevision, String subjectVi, String subjectEn,
            String textVi, String textEn, String htmlVi, String htmlEn, long actorId) {
        lock(key);
        int latest = latest(key);
        if (expectedRevision != latest) throw conflict();
        String cleanedVi = validate(key, subjectVi, textVi, htmlVi);
        String effSubjectEn = subjectEn != null && !subjectEn.isBlank() ? subjectEn.trim() : subjectVi.trim();
        String effTextEn = textEn != null && !textEn.isBlank() ? textEn.trim() : textVi.trim();
        String cleanedEn = htmlEn != null && !htmlEn.isBlank() ? validate(key, effSubjectEn, effTextEn, htmlEn) : cleanedVi;
        if (subjectEn != null && !subjectEn.isBlank() || textEn != null && !textEn.isBlank()) {
            validate(key, effSubjectEn, effTextEn, htmlEn);
        }
        LocalizedText subjectI18n = LocalizedText.of(subjectVi.trim(), effSubjectEn);
        LocalizedText textI18n = LocalizedText.of(textVi.trim(), effTextEn);
        LocalizedText htmlI18n = cleanedVi == null && cleanedEn == null ? null : LocalizedText.of(cleanedVi, cleanedEn);
        jdbc.update("""
                INSERT INTO notification_service.mail_template_revisions
                (template_key,revision_number,subject_template,body_template,html_template,subject_i18n,body_i18n,html_i18n,created_by)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
                """, key, latest + 1, subjectVi.trim(), textVi.trim(), cleanedVi,
                subjectI18n.toJson(), textI18n.toJson(), htmlI18n == null ? null : htmlI18n.toJson(), actorId);
        return get(key);
    }

    @Transactional
    public Detail publish(String key, long revisionId, long expectedPublishedRevisionId, long actorId) {
        MailTemplate current = lock(key);
        if (current == null) throw missing();
        var published = jdbc.queryForList("SELECT published_revision_id FROM notification_service.mail_templates WHERE template_key=?", key);
        if (published.isEmpty()) throw missing();
        Object previousId = published.getFirst().get("published_revision_id");
        if (previousId == null || ((Number) previousId).longValue() != expectedPublishedRevisionId) throw conflict();
        Revision revision = requireRevision(key, revisionId);
        if (revision.revision() != latest(key) || revision.publishedAt() != null) throw conflict();
        String viSubject = revision.subjectI18n() != null ? revision.subjectI18n().resolve("vi") : revision.subject();
        String viText = revision.textI18n() != null ? revision.textI18n().resolve("vi") : revision.text();
        String viHtml = revision.htmlI18n() != null ? revision.htmlI18n().resolve("vi") : revision.html();
        String cleanedVi = validate(key, viSubject, viText, viHtml);
        LocalizedText subjectI18n = revision.subjectI18n() != null ? revision.subjectI18n() : LocalizedText.ofSingle(viSubject);
        LocalizedText textI18n = revision.textI18n() != null ? revision.textI18n() : LocalizedText.ofSingle(viText);
        LocalizedText htmlI18n = revision.htmlI18n() != null ? revision.htmlI18n() : (cleanedVi == null ? null : LocalizedText.ofSingle(cleanedVi));
        // Existing notification_service.configuration_audit trigger records the publishing actor.
        jdbc.queryForObject("SELECT set_config('clucky.actor_id', ?, true)", String.class, String.valueOf(actorId));
        jdbc.update("""
                UPDATE notification_service.mail_templates
                SET subject_template=?,body_template=?,html_template=?,
                    subject_i18n=?::jsonb,body_i18n=?::jsonb,html_i18n=?::jsonb,
                    published_revision_id=?,updated_at=now()
                WHERE template_key=? AND is_active=TRUE
                """, viSubject, viText, cleanedVi,
                subjectI18n.toJson(), textI18n.toJson(), htmlI18n == null ? null : htmlI18n.toJson(),
                revisionId, key);
        jdbc.update("UPDATE notification_service.mail_template_revisions SET published_at=now(),published_by=? WHERE id=?", actorId, revisionId);
        return get(key);
    }

    @Transactional
    public Detail restore(String key, long revisionId, int expectedRevision, long actorId) {
        lock(key);
        if (latest(key) != expectedRevision) throw conflict();
        Revision source = requireRevision(key, revisionId);
        return saveDraft(key, expectedRevision,
                source.subjectI18n() != null ? source.subjectI18n().resolve("vi") : source.subject(),
                source.subjectI18n() != null ? source.subjectI18n().resolve("en") : null,
                source.textI18n() != null ? source.textI18n().resolve("vi") : source.text(),
                source.textI18n() != null ? source.textI18n().resolve("en") : null,
                source.htmlI18n() != null ? source.htmlI18n().resolve("vi") : source.html(),
                source.htmlI18n() != null ? source.htmlI18n().resolve("en") : null,
                actorId);
    }

    @Transactional(readOnly = true)
    public Preview preview(String key, String subject, String text, String html) {
        return preview(key, subject, text, html, "vi");
    }

    @Transactional(readOnly = true)
    public Preview preview(String key, String subject, String text, String html, String lang) {
        if (templates.findByTemplateKeyAndIsActiveTrue(key).isEmpty()) throw missing();
        String resolvedLang = "en".equalsIgnoreCase(lang) ? "en" : "vi";
        boolean isEn = "en".equals(resolvedLang);
        String cleaned = validate(key, subject, text, html);
        Map<String, String> variables = Map.ofEntries(
                Map.entry("locale", resolvedLang),
                Map.entry("customerName", isEn ? "Alex Nguyen" : "Nguyễn Văn A"), Map.entry("orderCode", "CL-2026-0012"),
                Map.entry("readyInstruction", isEn ? "Please pick up your order at the counter." : "Vui lòng đến quầy nhận món."),
                Map.entry("reason", isEn ? "Cancelled upon request." : "Đơn được hủy theo yêu cầu."), Map.entry("amount", "150000"),
                Map.entry("paymentExplanation", isEn ? "The transaction is currently being verified." : "Giao dịch đang được kiểm tra."),
                Map.entry("role", "STORE_MANAGER"), Map.entry("login", "branch.manager"),
                Map.entry("accountId", "101"), Map.entry("challengeId", "00000000-0000-0000-0000-000000000001"),
                Map.entry("otp", key.startsWith("BRANCH_") ? "12345678" : "123456"),
                Map.entry("orderItems", "[]"), Map.entry("orderId", "123"), Map.entry("orderType", "PICKUP"));
        Map<String, String> output = renderer.render(key, subject.trim(), text.trim(), cleaned,
                variables, "preview.primary", Instant.now().plusSeconds(600));
        return new Preview(output.get("subject"), output.get("body"), output.get("html"));
    }

    private MailTemplate lock(String key) {
        var ids = jdbc.queryForList("SELECT id FROM notification_service.mail_templates WHERE template_key=? AND is_active=TRUE FOR UPDATE", key);
        if (ids.isEmpty()) throw missing();
        return templates.findByTemplateKeyAndIsActiveTrue(key).orElseThrow(this::missing);
    }

    private int latest(String key) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision_number),0) FROM notification_service.mail_template_revisions
                WHERE template_key=?
                """, Integer.class, key);
        return value == null ? 0 : value;
    }

    private Revision requireRevision(String key, long revisionId) {
        String lang = LocaleResolverHelper.currentRequestLang();
        return jdbc.query("""
                SELECT * FROM notification_service.mail_template_revisions WHERE id=? AND template_key=?
                """, (rs, index) -> mapRevision(rs, lang),
                revisionId, key).stream().findFirst().orElseThrow(this::missing);
    }

    private Revision mapRevision(java.sql.ResultSet rs, String lang) throws java.sql.SQLException {
        String rawSubject = rs.getString("subject_template");
        String rawText = rs.getString("body_template");
        String rawHtml = rs.getString("html_template");
        LocalizedText subjectI18n = LocalizedText.fromDbValue(rs.getString("subject_i18n"));
        if (subjectI18n == null) subjectI18n = LocalizedText.ofSingle(rawSubject);
        LocalizedText textI18n = LocalizedText.fromDbValue(rs.getString("body_i18n"));
        if (textI18n == null) textI18n = LocalizedText.ofSingle(rawText);
        LocalizedText htmlI18n = LocalizedText.fromDbValue(rs.getString("html_i18n"));
        if (htmlI18n == null && rawHtml != null) htmlI18n = LocalizedText.ofSingle(rawHtml);
        return new Revision(
                rs.getLong("id"),
                rs.getInt("revision_number"),
                LocalizedText.resolve(subjectI18n, lang),
                subjectI18n,
                LocalizedText.resolve(textI18n, lang),
                textI18n,
                htmlI18n != null ? htmlI18n.resolve(lang) : rawHtml,
                htmlI18n,
                rs.getLong("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()
        );
    }

    private static String validate(String key, String subject, String text, String html) {
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.contains("\r") || subject.contains("\n")
                || text == null || text.isBlank() || text.length() > 100000
                || (html != null && html.length() > 100000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template size or subject");
        }
        validateVariables(key, subject, text, html);
        String cleaned;
        try {
            cleaned = EmailTemplateRenderer.sanitizeHtml(html);
            EmailTemplateRenderer.requireFullDocumentSlots(cleaned);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        if (html != null && (cleaned == null || cleaned.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email HTML body is empty");
        }
        validateVariables(key, subject, text, cleaned);
        return cleaned;
    }

    private static void validateVariables(String key, String subject, String text, String html) {
        Set<String> allowed = key.startsWith("BRANCH_") ? BRANCH_VARIABLES
                : TRANSACTION_VARIABLES.getOrDefault(key, Set.of());
        boolean document = EmailHtmlSanitizer.isFullDocument(html);
        for (String value : List.of(subject, text, html == null ? "" : html)) {
            var tokens = VARIABLE.matcher(value);
            while (tokens.find()) {
                if (!allowed.contains(tokens.group(1))
                        && !(document && value.equals(html) && DOCUMENT_VARIABLES.contains(tokens.group(1)))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported email variable");
                }
            }
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Template changed; reload before saving");
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown email template or revision");
    }
}
