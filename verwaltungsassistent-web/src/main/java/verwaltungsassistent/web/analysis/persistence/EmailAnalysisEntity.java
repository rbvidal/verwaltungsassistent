package verwaltungsassistent.web.analysis.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted citizen e-mail analysis (the {@code EmailOutcome} result of
 * the E-Mail panel). Analyses survive navigation and restarts so a previous
 * result stays reproducible.
 *
 * <p>Cache design for a later exact-question reuse step — do not key on the
 * last indexing date alone. A proper cache key must combine:
 * <ol>
 *   <li>the exact question text ({@link #questionText});</li>
 *   <li>a knowledge-state fingerprint: identities and versions of the
 *       documents whose chunks feed the search (an analysis is stale once
 *       any evidence document is replaced/removed or a new document could
 *       change the ranking);</li>
 *   <li>a configuration fingerprint: {@link #appVersion} plus the effective
 *       model/prompt settings (Ollama chat model, semantic-intent flag,
 *       retrieval weights).</li>
 * </ol>
 * {@link #appVersion} is stored per analysis as groundwork for that key.
 * Because case matching is user-specific (owner + admin), cached results
 * must never be served to a different user than the one who ran them.</p>
 */
@Entity
@Table(name = "email_analyses")
public class EmailAnalysisEntity {

    @Id
    private UUID id;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    /** The exact free-text the user submitted for analysis. */
    @Column(name = "question_text", columnDefinition = "text", nullable = false)
    private String questionText;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "topic")
    private String topic;

    /** App version at analysis time (single version source: pom.xml). */
    @Column(name = "app_version")
    private String appVersion;

    /** The serialized EmailOutcome of the completed analysis. */
    @Column(name = "result_json", columnDefinition = "text", nullable = false)
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Antwortentwurf (Phase 2C.8): generierter Entwurfstext — rein kommunikativer
     *  Arbeitsstand, wird NIE versendet und verändert weder E-Mail noch Vorgang. */
    @Column(name = "draft_text", columnDefinition = "text")
    private String draftText;

    @Column(name = "draft_generated_at")
    private Instant draftGeneratedAt;

    /** true sobald die Mitarbeiterin den Entwurf manuell geändert hat (Schutz
     *  vor stillschweigendem Überschreiben beim Neu-Generieren). */
    @Column(name = "draft_edited")
    private Boolean draftEdited;

    /** Lokaler Prüfstand (Phase 2C.9): Mitarbeiterin hat den Entwurf geprüft —
     *  rein interner Vermerk, KEIN Versand, keine Status-/Falländerung. */
    @Column(name = "draft_reviewed_at")
    private Instant draftReviewedAt;

    @Column(name = "draft_reviewed_by")
    private String draftReviewedBy;

    protected EmailAnalysisEntity() {
    }

    public EmailAnalysisEntity(UUID id, String userEmail, String questionText, String subject,
                               String topic, String appVersion, String resultJson, Instant createdAt) {
        this.id = id;
        this.userEmail = userEmail;
        this.questionText = questionText;
        this.subject = subject;
        this.topic = topic;
        this.appVersion = appVersion;
        this.resultJson = resultJson;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDraftText() {
        return draftText;
    }

    public void setDraftText(String draftText) {
        this.draftText = draftText;
    }

    public Instant getDraftGeneratedAt() {
        return draftGeneratedAt;
    }

    public void setDraftGeneratedAt(Instant draftGeneratedAt) {
        this.draftGeneratedAt = draftGeneratedAt;
    }

    public Boolean getDraftEdited() {
        return draftEdited;
    }

    public void setDraftEdited(Boolean draftEdited) {
        this.draftEdited = draftEdited;
    }

    /** true sobald die Mitarbeiterin den Entwurf manuell geändert hat. */
    public boolean isDraftEdited() {
        return Boolean.TRUE.equals(draftEdited);
    }

    public Instant getDraftReviewedAt() {
        return draftReviewedAt;
    }

    public void setDraftReviewedAt(Instant draftReviewedAt) {
        this.draftReviewedAt = draftReviewedAt;
    }

    public String getDraftReviewedBy() {
        return draftReviewedBy;
    }

    public void setDraftReviewedBy(String draftReviewedBy) {
        this.draftReviewedBy = draftReviewedBy;
    }

    /** true wenn der Entwurf lokal geprüft wurde (nur interner Vermerk). */
    public boolean isDraftReviewed() {
        return draftReviewedAt != null;
    }
}
