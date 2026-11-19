package com.clucky.identityservice.service;

import com.clucky.identityservice.config.CurrentActor;
import com.clucky.identityservice.dto.Role;
import com.clucky.identityservice.dto.auth.LegalConsentRequest;
import com.clucky.identityservice.exception.ApiException;
import com.clucky.identityservice.exception.ErrorCode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class LegalDocumentService {
    public static final String TERMS = "TERMS_OF_SERVICE";
    public static final String PRIVACY = "PRIVACY_POLICY";
    private final JdbcTemplate jdbc;
    private final IdentityAuditService audit;

    public LegalDocumentService(JdbcTemplate jdbc, IdentityAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Revision(long id, String type, int version, String titleVi, String titleEn,
                           String htmlVi, String htmlEn, Instant createdAt, Long createdBy,
                           Instant publishedAt) {}
    public record Overview(String type, int latestRevision, Integer draftVersion, Integer publishedVersion,
                           Revision draft, Revision published) {}
    public record DraftInput(String titleVi, String titleEn, String htmlVi, String htmlEn,
                             Integer expectedDraftVersion, Integer expectedRevision) {}
    public record PublishedRequest(int version) {}
    public record RestoreRequest(Integer expectedDraftVersion, Integer expectedRevision) {}
    /** At least one revision must be present; the other may be absent until Admin publishes it. */
    public record Approved(Long termsId, Long privacyId) {}
    public record PublicStatus(boolean consentRequired, boolean termsPublished,
                               boolean privacyPublished, boolean documentsPublished) {}

    /** Read-only publication state. Registration validates again under transaction-scoped row locks. */
    @Transactional(readOnly = true)
    public PublicStatus publicStatus() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.document_type, (d.published_revision_id IS NOT NULL AND r.id IS NOT NULL "
                + "AND r.published_at IS NOT NULL) AS published "
                + "FROM identity_service.legal_documents d "
                + "LEFT JOIN identity_service.legal_revisions r "
                + "ON r.id=d.published_revision_id AND r.document_type=d.document_type "
                + "WHERE d.document_type IN (?,?) ORDER BY d.document_type", TERMS, PRIVACY);
        if (rows.size() != 2) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        boolean terms = false, privacy = false;
        for (Map<String, Object> row : rows) {
            if (TERMS.equals(row.get("document_type"))) terms = Boolean.TRUE.equals(row.get("published"));
            else if (PRIVACY.equals(row.get("document_type"))) privacy = Boolean.TRUE.equals(row.get("published"));
            else throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return new PublicStatus(terms || privacy, terms, privacy, terms && privacy);
    }

    private static String documentType(String type) {
        if (!TERMS.equals(type) && !PRIVACY.equals(type)) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return type;
    }

    private void requireAdmin() {
        CurrentActor actor = CurrentActor.require();
        if (actor.role() != Role.ADMIN) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Revision revision(ResultSet rs, int ignored) throws SQLException {
        return new Revision(rs.getLong("id"), rs.getString("document_type"), rs.getInt("version"),
                rs.getString("title_vi"), rs.getString("title_en"),
                rs.getString("html_vi"), rs.getString("html_en"),
                instant(rs, "created_at"), rs.getLong("created_by"), instant(rs, "published_at"));
    }

    private Revision one(String type, long id) {
        return jdbc.query("SELECT * FROM identity_service.legal_revisions WHERE document_type=? AND id=?",
                LegalDocumentService::revision, type, id).stream()
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Revision version(String type, int version) {
        return jdbc.query("SELECT * FROM identity_service.legal_revisions WHERE document_type=? AND version=?",
                LegalDocumentService::revision, type, version).stream()
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Revision published(String type) {
        String checked = documentType(type);
        List<Long> ids = jdbc.queryForList("SELECT published_revision_id FROM identity_service.legal_documents "
                        + "WHERE document_type=? AND published_revision_id IS NOT NULL",
                Long.class, checked);
        if (ids.isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        return one(checked, ids.getFirst());
    }

    @Transactional(readOnly = true)
    public Overview overview(String type) {
        requireAdmin();
        String checked = documentType(type);
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT revision_counter, draft_revision_id, published_revision_id FROM identity_service.legal_documents WHERE document_type=?",
                checked);
        Long draft = number(current.get("draft_revision_id"));
        Long published = number(current.get("published_revision_id"));
        Revision draftValue = draft == null ? null : one(checked, draft);
        Revision publicValue = published == null ? null : one(checked, published);
        return new Overview(checked, ((Number) current.get("revision_counter")).intValue(),
                draftValue == null ? null : draftValue.version(),
                publicValue == null ? null : publicValue.version(), draftValue, publicValue);
    }

    private static Long number(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    @Transactional(readOnly = true)
    public List<Revision> history(String type) {
        requireAdmin();
        return jdbc.query("SELECT * FROM identity_service.legal_revisions WHERE document_type=? ORDER BY version DESC LIMIT 100",
                LegalDocumentService::revision, documentType(type));
    }

    @Transactional(readOnly = true)
    public Revision preview(String type, int version) {
        requireAdmin();
        return version(documentType(type), version);
    }

    /** Output stored in DB is safe for HTML rendering; server never trusts editor markup. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank() || html.length() > 100_000) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        Safelist allowed = new Safelist()
                .addTags("p", "br", "h1", "h2", "h3", "h4", "ul", "ol", "li",
                        "b", "strong", "i", "em", "u", "s", "blockquote", "pre", "code", "hr", "a")
                .addAttributes("a", "href")
                .addProtocols("a", "href", "http", "https")
                .addEnforcedAttribute("a", "rel", "noopener noreferrer");
        Document.OutputSettings output = new Document.OutputSettings().prettyPrint(false);
        String safe = Jsoup.clean(html, "", allowed, output);
        if (Jsoup.parseBodyFragment(safe).text().isBlank()) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        return safe;
    }

    private static String title(String title) {
        if (title == null || title.isBlank() || title.length() > 160)
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        return title.strip();
    }

    private Revision insertDraft(String type, DraftInput input) {
        // Serializes version counter, restore, draft replacement and publish.
        Map<String, Object> locked = jdbc.queryForMap("SELECT d.revision_counter, r.version AS draft_version "
                + "FROM identity_service.legal_documents d LEFT JOIN identity_service.legal_revisions r "
                + "ON r.id=d.draft_revision_id WHERE d.document_type=? FOR UPDATE OF d", type);
        int next = ((Number) locked.get("revision_counter")).intValue();
        Integer draftVersion = locked.get("draft_version") == null
                ? null : ((Number) locked.get("draft_version")).intValue();
        requireExpectedDraft(input.expectedRevision(), input.expectedDraftVersion(), next, draftVersion);
        var actor = CurrentActor.require();
        long id = jdbc.queryForObject("INSERT INTO identity_service.legal_revisions "
                + "(document_type,version,title_vi,title_en,html_vi,html_en,created_by) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id", Long.class, type, next + 1,
                title(input.titleVi()), title(input.titleEn()),
                sanitize(input.htmlVi()), sanitize(input.htmlEn()), actor.userId());
        jdbc.update("UPDATE identity_service.legal_documents SET draft_revision_id=?, revision_counter=?, updated_at=now() "
                + "WHERE document_type=?", id, next + 1, type);
        audit.record(actor.userId(), actor.role().name(), null, null, "LEGAL_DRAFT_SAVED", id,
                "LegalRevision", Map.of("type", type, "version", next + 1), null);
        return one(type, id);
    }

    /** Compare-and-set *after* obtaining the document row lock: no stale Admin tab may replace a newer draft. */
    static void requireExpectedDraft(Integer expectedRevision, Integer expectedDraftVersion,
                                     int currentRevision, Integer currentDraftVersion) {
        if (expectedRevision == null || expectedRevision < 0
                || expectedRevision != currentRevision
                || !Objects.equals(expectedDraftVersion, currentDraftVersion)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, Map.of("draft", "OUTDATED"));
        }
    }

    @Transactional
    public Revision saveDraft(String type, DraftInput input) {
        requireAdmin();
        if (input == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        return insertDraft(documentType(type), input);
    }

    @Transactional
    public Revision restore(String type, int sourceVersion, RestoreRequest request) {
        requireAdmin();
        if (request == null) throw new ApiException(ErrorCode.RESOURCE_CONFLICT, Map.of("draft", "OUTDATED"));
        String checked = documentType(type);
        Revision old = version(checked, sourceVersion);
        Revision restored = insertDraft(checked,
                new DraftInput(old.titleVi(), old.titleEn(), old.htmlVi(), old.htmlEn(),
                        request.expectedDraftVersion(), request.expectedRevision()));
        CurrentActor actor = CurrentActor.require();
        audit.record(actor.userId(), actor.role().name(), null, null, "LEGAL_VERSION_RESTORED",
                restored.id(), "LegalRevision", Map.of("type", checked, "sourceVersion", sourceVersion,
                        "newVersion", restored.version()), null);
        return restored;
    }

    @Transactional
    public Revision publish(String type, int version) {
        requireAdmin();
        String checked = documentType(type);
        if (version < 1) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Map<String, Object> document = jdbc.queryForMap("SELECT draft_revision_id FROM identity_service.legal_documents "
                + "WHERE document_type=? FOR UPDATE", checked);
        Long draftId = number(document.get("draft_revision_id"));
        if (draftId == null) throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
        Revision target = one(checked, draftId);
        if (target.version() != version) throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
        jdbc.update("UPDATE identity_service.legal_revisions SET published_at=COALESCE(published_at,now()) WHERE id=?",
                draftId);
        jdbc.update("UPDATE identity_service.legal_documents "
                + "SET published_revision_id=?, draft_revision_id=NULL, updated_at=now() WHERE document_type=?",
                draftId, checked);
        CurrentActor actor = CurrentActor.require();
        audit.record(actor.userId(), actor.role().name(), null, null, "LEGAL_VERSION_PUBLISHED",
                draftId, "LegalRevision", Map.of("type", checked, "version", version), null);
        return one(checked, draftId);
    }

    /**
     * Admin-only, compare-and-set unpublish. The document row lock also
     * serializes with concurrent signup's FOR SHARE OF d and publish/draft.
     * History remains immutable and no revision is deleted.
     */
    @Transactional
    public void unpublish(String type, int expectedVersion) {
        requireAdmin();
        String checked = documentType(type);
        if (expectedVersion < 1) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        Map<String, Object> locked = jdbc.queryForMap(
                "SELECT d.published_revision_id, r.version AS published_version "
                + "FROM identity_service.legal_documents d LEFT JOIN identity_service.legal_revisions r "
                + "ON r.id=d.published_revision_id AND r.document_type=d.document_type "
                + "WHERE d.document_type=? FOR UPDATE OF d", checked);
        Long currentId = number(locked.get("published_revision_id"));
        Object currentVersion = locked.get("published_version");
        if (currentId != null && currentVersion == null)
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        if (currentId == null || ((Number) currentVersion).intValue() != expectedVersion)
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, Map.of("published", "OUTDATED"));
        jdbc.update("UPDATE identity_service.legal_documents SET published_revision_id=NULL, updated_at=now() "
                + "WHERE document_type=?", checked);
        CurrentActor actor = CurrentActor.require();
        audit.record(actor.userId(), actor.role().name(), null, null, "LEGAL_VERSION_UNPUBLISHED",
                currentId, "LegalRevision", Map.of("type", checked, "version", expectedVersion), null);
    }

    /**
     * Lock BOTH document rows, including unpublished ones, until the enclosing
     * registration transaction commits. A concurrent publish waits rather than
     * changing which revisions require consent mid-signup.
     */
    @Transactional
    public Approved requireCurrentConsent(LegalConsentRequest accepted) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.document_type, d.published_revision_id AS id, r.version, r.published_at "
                + "FROM identity_service.legal_documents d "
                + "LEFT JOIN identity_service.legal_revisions r "
                + "ON r.id=d.published_revision_id AND r.document_type=d.document_type "
                + "WHERE d.document_type IN (?,?) ORDER BY d.document_type FOR SHARE OF d",
                TERMS, PRIVACY);
        // Missing document rows or dangling/unpublished pointers are an invalid
        // backend state, never a reason to silently allow registration.
        if (rows.size() != 2) throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        Long termsId = null, privacyId = null;
        Integer termsVersion = null, privacyVersion = null;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            Object version = row.get("version");
            Object publishedAt = row.get("published_at");
            if (id != null && (version == null || publishedAt == null))
                throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            if (TERMS.equals(row.get("document_type"))) {
                termsId = number(id);
                termsVersion = version == null ? null : ((Number) version).intValue();
            } else if (PRIVACY.equals(row.get("document_type"))) {
                privacyId = number(id);
                privacyVersion = version == null ? null : ((Number) version).intValue();
            } else throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (termsId == null && privacyId == null) {
            // A caller may omit consent while there are no published documents.
            // A purported acceptance/version is synthetic and must not be saved.
            if (accepted != null) throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    Map.of("consent", "NOT_REQUIRED"));
            return null;
        }
        if (accepted == null || !Boolean.TRUE.equals(accepted.accepted()))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("consent", "REQUIRED"));
        if ((termsVersion != null && accepted.termsVersion() == null)
                || (privacyVersion != null && accepted.privacyVersion() == null))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("consent", "REQUIRED"));
        if (!Objects.equals(termsVersion, accepted.termsVersion())
                || !Objects.equals(privacyVersion, accepted.privacyVersion()))
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, Map.of("consent", "OUTDATED"));
        return new Approved(termsId, privacyId);
    }

    @Transactional
    public void recordAcceptance(long userId, Approved approved, String source) {
        if (approved == null) return; // No published documents at the locked signup snapshot.
        if (!"PHONE_REGISTER".equals(source) && !"GOOGLE_COMPLETE".equals(source))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        if (approved.termsId() == null && approved.privacyId() == null)
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        jdbc.update("INSERT INTO identity_service.legal_consents "
                + "(user_id,terms_revision_id,privacy_revision_id,source) VALUES (?,?,?,?)",
                userId, approved.termsId(), approved.privacyId(), source);
    }
}
