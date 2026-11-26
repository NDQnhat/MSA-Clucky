package com.clucky.notificationservice.entity;

import com.clucky.notificationservice.dto.LocalizedText;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mail_templates", schema = "notification_service")
public class MailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "template_key", nullable = false, length = 100, unique = true)
    private String templateKey;

    @Column(name = "subject_template", nullable = false, columnDefinition = "text")
    private String subjectTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subject_i18n", columnDefinition = "jsonb")
    private LocalizedText subjectI18n;

    @Column(name = "body_template", nullable = false, columnDefinition = "text")
    private String bodyTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body_i18n", columnDefinition = "jsonb")
    private LocalizedText bodyI18n;

    @Column(name = "html_template", columnDefinition = "text")
    private String htmlTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "html_i18n", columnDefinition = "jsonb")
    private LocalizedText htmlI18n;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }

    public String getSubjectTemplate() { return subjectTemplate; }
    public void setSubjectTemplate(String subjectTemplate) { this.subjectTemplate = subjectTemplate; }
    public LocalizedText getSubjectI18n() { return subjectI18n; }
    public void setSubjectI18n(LocalizedText subjectI18n) { this.subjectI18n = subjectI18n; }
    public String resolveSubjectTemplate(String lang) {
        return subjectI18n != null ? subjectI18n.resolve(lang) : subjectTemplate;
    }

    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public LocalizedText getBodyI18n() { return bodyI18n; }
    public void setBodyI18n(LocalizedText bodyI18n) { this.bodyI18n = bodyI18n; }
    public String resolveBodyTemplate(String lang) {
        return bodyI18n != null ? bodyI18n.resolve(lang) : bodyTemplate;
    }

    public String getHtmlTemplate() { return htmlTemplate; }
    public void setHtmlTemplate(String htmlTemplate) { this.htmlTemplate = htmlTemplate; }
    public LocalizedText getHtmlI18n() { return htmlI18n; }
    public void setHtmlI18n(LocalizedText htmlI18n) { this.htmlI18n = htmlI18n; }
    public String resolveHtmlTemplate(String lang) {
        if (htmlI18n != null && htmlI18n.resolve(lang) != null && !htmlI18n.resolve(lang).isBlank()) {
            return htmlI18n.resolve(lang);
        }
        return htmlTemplate;
    }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @PreUpdate
    protected void touch() { updatedAt = Instant.now(); }
}
