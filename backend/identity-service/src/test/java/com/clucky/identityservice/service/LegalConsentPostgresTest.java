package com.clucky.identityservice.service;

import com.clucky.identityservice.dto.auth.LegalConsentRequest;
import com.clucky.identityservice.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Isolated PostgreSQL integration check. It never connects unless
 * LEGAL_PG_URL is explicitly set to a disposable test database.
 */
class LegalConsentPostgresTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "LEGAL_PG_URL", matches = ".+")
    void migrationPartialAcceptanceAndConcurrentUnpublishAreSafe() throws Exception {
        var ds = new DriverManagerDataSource(
                System.getenv("LEGAL_PG_URL"),
                System.getenv().getOrDefault("LEGAL_PG_USER", "postgres"),
                System.getenv("LEGAL_PG_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        // Only run against a fresh, disposable DB. Never drop existing tables.
        Integer schemas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name='identity_service'",
                Integer.class);
        assertEquals(0, schemas, "Refusing to touch an existing identity_service schema");
        jdbc.execute("CREATE SCHEMA identity_service");
        jdbc.execute("CREATE TABLE identity_service.users (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO identity_service.users(id) VALUES (7)");

        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V15__legal_documents_and_consents.sql")), ds);
        Long terms = jdbc.queryForObject("INSERT INTO identity_service.legal_revisions "
                + "(document_type,version,title_vi,title_en,html_vi,html_en,created_by,published_at) "
                + "VALUES ('TERMS_OF_SERVICE',1,'Điều khoản','Terms','<p>Vi</p>','<p>En</p>',7,now()) "
                + "RETURNING id", Long.class);
        Long privacy = jdbc.queryForObject("INSERT INTO identity_service.legal_revisions "
                + "(document_type,version,title_vi,title_en,html_vi,html_en,created_by,published_at) "
                + "VALUES ('PRIVACY_POLICY',1,'Quyền riêng tư','Privacy','<p>Vi</p>','<p>En</p>',7,now()) "
                + "RETURNING id", Long.class);
        jdbc.update("UPDATE identity_service.legal_documents SET published_revision_id=? "
                + "WHERE document_type='TERMS_OF_SERVICE'", terms);
        jdbc.update("UPDATE identity_service.legal_documents SET published_revision_id=? "
                + "WHERE document_type='PRIVACY_POLICY'", privacy);
        // A pre-existing V15 record contains both revision IDs; V16 must
        // preserve it without changes before allowing partial acceptances.
        jdbc.update("INSERT INTO identity_service.legal_consents "
                + "(user_id,terms_revision_id,privacy_revision_id,source) "
                + "VALUES (7,?,?,'PHONE_REGISTER')", terms, privacy);
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V16__allow_partial_legal_consent.sql")), ds);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_service.legal_consents WHERE user_id=7 "
                + "AND terms_revision_id=? AND privacy_revision_id=? AND accepted_at IS NOT NULL",
                Integer.class, terms, privacy));
        for (String column : new String[] {"terms_revision_id", "privacy_revision_id"}) {
            assertEquals("YES", jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema='identity_service' "
                    + "AND table_name='legal_consents' AND column_name=?", String.class, column));
        }

        var legal = new LegalDocumentService(jdbc, mock(IdentityAuditService.class));
        assertEquals(new LegalDocumentService.PublicStatus(true, true, true, true), legal.publicStatus());
        jdbc.update("UPDATE identity_service.legal_documents SET published_revision_id=NULL "
                + "WHERE document_type='PRIVACY_POLICY'");
        assertEquals(new LegalDocumentService.PublicStatus(true, true, false, false), legal.publicStatus());

        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(ignored -> {
                var approved = legal.requireCurrentConsent(new LegalConsentRequest(true, 1, null));
                assertEquals(terms, approved.termsId());
                assertNull(approved.privacyId());
                // Different connection may not unpublish until signup releases
                // the FOR SHARE OF d locks. PostgreSQL reports 55P03 lock timeout.
                var future = executor.submit(() -> {
                    try (Connection other = ds.getConnection();
                         var statement = other.createStatement()) {
                        statement.execute("SET lock_timeout='350ms'");
                        statement.executeUpdate("UPDATE identity_service.legal_documents "
                                + "SET published_revision_id=NULL WHERE document_type='TERMS_OF_SERVICE'");
                        return "UPDATED";
                    } catch (SQLException failure) {
                        return failure.getSQLState();
                    }
                });
                try {
                    assertEquals("55P03", future.get(5, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("Concurrent unpublish did not wait for signup", failure);
                }
                legal.recordAcceptance(7L, approved, "PHONE_REGISTER");
            });
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity_service.legal_consents WHERE user_id=7 "
                + "AND terms_revision_id=? AND privacy_revision_id IS NULL AND accepted_at IS NOT NULL",
                Integer.class, terms));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO identity_service.legal_consents "
                + "(user_id,terms_revision_id,privacy_revision_id,source) "
                + "VALUES (7,NULL,NULL,'PHONE_REGISTER')"));
        jdbc.update("UPDATE identity_service.legal_documents SET published_revision_id=NULL "
                + "WHERE document_type='TERMS_OF_SERVICE'");
        assertEquals(new LegalDocumentService.PublicStatus(false, false, false, false), legal.publicStatus());
        assertNull(tx.execute(ignored -> legal.requireCurrentConsent(null)));
        assertThrows(ApiException.class, () -> tx.execute(ignored ->
                legal.requireCurrentConsent(new LegalConsentRequest(true, 1, null))));
    }
}
