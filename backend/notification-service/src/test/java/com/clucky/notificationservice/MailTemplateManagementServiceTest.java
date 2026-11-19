package com.clucky.notificationservice;

import com.clucky.notificationservice.entity.MailTemplate;
import com.clucky.notificationservice.repository.MailTemplateRepository;
import com.clucky.notificationservice.service.EmailTemplateRenderer;
import com.clucky.notificationservice.service.MailTemplateManagementService;
import com.clucky.notificationservice.service.MailTemplateManagementService.Summary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailTemplateManagementServiceTest {
    private static final String COMPLETE_HTML = """
            <!doctype html><html lang="vi"><head><title>{{subject}}</title>
            <style>@media screen and (max-width:600px) {.box{width:100%!important;}}</style></head>
            <body><table class="box" style="width:100%"><tr><td>{{content}}</td></tr>
            <tr><td>{{details}}</td></tr>{{action}}
            <tr><td>{{notice}}</td></tr><tr><td>{{footer}}</td></tr>
            <tr><td><b>{{customerName}}</b></td></tr></table></body></html>
            """;

    @Test
    void fullHtmlDraftStoresSanitizedWholeDocumentAndPreviewRendersIt() throws Exception {
        var key = "ORDER_CONFIRMED";
        var jdbc = mock(JdbcTemplate.class);
        var repository = mock(MailTemplateRepository.class);
        when(repository.findByTemplateKeyAndIsActiveTrue(key)).thenReturn(Optional.of(new MailTemplate()));
        when(jdbc.queryForList(contains("SELECT id FROM"), eq(key)))
                .thenReturn(List.of(Map.of("id", 12L)));
        when(jdbc.queryForObject(contains("COALESCE(MAX(revision_number)"), eq(Integer.class), eq(key)))
                .thenReturn(1);
        doReturn(List.of(new Summary(key, "Subject", true, Instant.now(), 10L, 2)))
                .when(jdbc).query(contains("SELECT t.template_key"), any(RowMapper.class));
        doReturn(List.of()).when(jdbc).query(contains("SELECT * FROM notification_service.mail_template_revisions"),
                any(RowMapper.class), eq(key));
        var service = new MailTemplateManagementService(jdbc, repository,
                new EmailTemplateRenderer("https://clucky.example"));
        String submitted = COMPLETE_HTML.replace("<body>", "<body onload=\"alert(1)\"><script>alert(2)</script>");

        assertNotNull(service.saveDraft(key, 1, "Đơn {{orderCode}}", "Chào {{customerName}}", submitted, 99));
        verify(jdbc).update(contains("INSERT INTO notification_service.mail_template_revisions"),
                eq(key), eq(2), eq("Đơn {{orderCode}}"), eq("Chào {{customerName}}"),
                argThat(html -> html instanceof String value
                        && value.startsWith("<!doctype html>")
                        && value.contains("<head>")
                        && value.contains("<style>")
                        && value.contains("{{action}}")
                        && value.contains("{{details}}")
                        && !value.contains("<script")
                        && !value.contains("onload=")), eq(99L));
        var preview = service.preview(key, "Đơn {{orderCode}}", "Chào {{customerName}}", submitted);
        assertTrue(preview.html().contains("<head>"));
        assertTrue(preview.html().contains("CL-2026-0012"));
        assertTrue(preview.html().contains("href=\"https://clucky.example/orders/123\""));
        assertFalse(preview.html().contains("{{"));
        assertTrue(service.layout().html().startsWith("<!doctype html>"));
    }

    @Test
    void rejectsIncompleteOrMisplacedFullHtmlEmailSlots() throws Exception {
        var repository = mock(MailTemplateRepository.class);
        when(repository.findByTemplateKeyAndIsActiveTrue("ORDER_CONFIRMED"))
                .thenReturn(Optional.of(new MailTemplate()));
        var service = new MailTemplateManagementService(mock(JdbcTemplate.class), repository,
                new EmailTemplateRenderer("https://clucky.example"));
        for (String incomplete : List.of(
                COMPLETE_HTML.replace("{{details}}", ""),
                COMPLETE_HTML.replace("{{action}}", ""),
                COMPLETE_HTML.replace("{{notice}}", ""),
                COMPLETE_HTML.replace("{{footer}}", ""),
                COMPLETE_HTML.replace("{{action}}", "").replace("{{footer}}", "{{action}}"))) {
            var invalid = assertThrows(ResponseStatusException.class,
                    () -> service.preview("ORDER_CONFIRMED", "Subject", "Body", incomplete));
            assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        }
        var attributeInjection = assertThrows(ResponseStatusException.class,
                () -> service.preview("ORDER_CONFIRMED", "Subject", "Body",
                        COMPLETE_HTML.replace("<table", "<table data-click=\"{{customerName}}\"")));
        assertEquals(HttpStatus.BAD_REQUEST, attributeInjection.getStatusCode());
        var blockInAttribute = assertThrows(ResponseStatusException.class,
                () -> service.preview("ORDER_CONFIRMED", "Subject", "Body",
                        COMPLETE_HTML.replace("<table", "<table title=\"{{details}}\"")));
        assertEquals(HttpStatus.BAD_REQUEST, blockInAttribute.getStatusCode());
        var encodedVariable = assertThrows(ResponseStatusException.class,
                () -> service.preview("ORDER_CONFIRMED", "Subject", "Body",
                        COMPLETE_HTML.replace("{{customerName}}",
                                "&#123;&#123;unsupportedVariable&#125;&#125;")));
        assertEquals(HttpStatus.BAD_REQUEST, encodedVariable.getStatusCode());
    }

    @Test
    void rejectsUnsupportedSecurityVariablesAndHeaderInjectionBeforeRendering() throws Exception {
        var repository = mock(MailTemplateRepository.class);
        when(repository.findByTemplateKeyAndIsActiveTrue("register-otp")).thenReturn(Optional.of(new MailTemplate()));
        var service = new MailTemplateManagementService(mock(JdbcTemplate.class), repository,
                new EmailTemplateRenderer("https://clucky.example"));

        var badVariable = assertThrows(ResponseStatusException.class, () ->
                service.preview("register-otp", "Xác thực", "Mã là {{otp}}", "<p>Mã {{otp}}</p>"));
        assertEquals(HttpStatus.BAD_REQUEST, badVariable.getStatusCode());

        var headerInjection = assertThrows(ResponseStatusException.class, () ->
                service.preview("register-otp", "Xác thực\r\nBcc: attacker@example.com", "Nội dung", null));
        assertEquals(HttpStatus.BAD_REQUEST, headerInjection.getStatusCode());
    }

    @Test
    void generatesSanitizedPreviewWithoutSendingEmail() throws Exception {
        var repository = mock(MailTemplateRepository.class);
        when(repository.findByTemplateKeyAndIsActiveTrue("ORDER_READY")).thenReturn(Optional.of(new MailTemplate()));
        var service = new MailTemplateManagementService(mock(JdbcTemplate.class), repository,
                new EmailTemplateRenderer("https://clucky.example"));

        var preview = service.preview("ORDER_READY", "Đơn {{orderCode}}", "Chào {{customerName}}",
                "<p><strong>{{customerName}}</strong><script>alert(1)</script></p>");
        assertTrue(preview.html().contains("<strong>Nguyễn Văn A</strong>"));
        assertFalse(preview.html().contains("<script>"));
        assertTrue(preview.text().contains("Nguyễn Văn A"));
    }

    @Test
    void rejectsVariablesMissingFromRealOrderAndPaymentEvents() throws Exception {
        var repository = mock(MailTemplateRepository.class);
        for (String key : new String[]{"ORDER_CREATED", "ORDER_CONFIRMED", "ORDER_CANCELLED", "PAYMENT_CONFIRMED", "PAYMENT_FAILED"}) {
            when(repository.findByTemplateKeyAndIsActiveTrue(key)).thenReturn(Optional.of(new MailTemplate()));
        }
        var service = new MailTemplateManagementService(mock(JdbcTemplate.class), repository,
                new EmailTemplateRenderer("https://clucky.example"));
        for (String[] invalid : new String[][]{
                {"ORDER_CREATED", "readyInstruction"},
                {"ORDER_CONFIRMED", "reason"},
                {"ORDER_CANCELLED", "readyInstruction"},
                {"PAYMENT_CONFIRMED", "paymentExplanation"},
                {"PAYMENT_FAILED", "amount"},
        }) {
            var result = assertThrows(ResponseStatusException.class,
                    () -> service.preview(invalid[0], "Subject", "Body {{" + invalid[1] + "}}", null),
                    invalid[0]);
            assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        }
        assertDoesNotThrow(() -> service.preview("ORDER_CANCELLED", "Đơn {{orderCode}}",
                "Lý do: {{reason}}", null));
        assertDoesNotThrow(() -> service.preview("PAYMENT_FAILED", "Đơn {{orderCode}}",
                "Vui lòng xem: {{paymentExplanation}}", null));
    }
}
