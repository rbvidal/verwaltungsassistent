package verwaltungsassistent.web.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobProgressServiceTest {

    private final JobProgressService service = new JobProgressService();

    @Test
    void createAndGet() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ANALYSIS, "Analyse läuft");
        assertNotNull(job.jobId);
        assertEquals(JobProgressService.Kind.ANALYSIS, job.kind);
        assertEquals("RUNNING", job.state);
        assertSame(job, service.get(job.jobId));
    }

    @Test
    void recordStage_appendsInOrderAndSkipsBlank() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage");
        service.recordStage(job.jobId, "Erste Stufe");
        service.recordStage(job.jobId, "  ");
        service.recordStage(job.jobId, null);
        service.recordStage(job.jobId, "Zweite Stufe");
        assertEquals(java.util.List.of("Erste Stufe", "Zweite Stufe"), job.messages);
    }

    @Test
    void recordStage_unknownJobIsIgnored() {
        service.recordStage("unknown", "Stufe");
        assertEquals(0, service.size());
    }

    @Test
    void complete_setsDoneStateAndOutcome() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.EVALUATION, "Fall");
        service.complete(job.jobId, "<div>ERGEBNIS</div>", "Fertig");
        assertEquals("DONE", job.state);
        assertEquals("<div>ERGEBNIS</div>", job.outcome);
        assertEquals("Fertig", job.terminalMessage);
    }

    @Test
    void fail_setsErrorState() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage");
        service.fail(job.jobId);
        assertEquals("ERROR", job.state);
    }

    @Test
    void activeJob_returnsRunningJobOnly() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.EVALUATION, "Fall");
        service.registerActive("evaluation:travel:T01", job.jobId);
        assertSame(job, service.activeJob("evaluation:travel:T01"));

        service.complete(job.jobId, "x", "fertig");
        assertNull(service.activeJob("evaluation:travel:T01"),
                "finished jobs must not block new starts");
    }

    @Test
    void unregisterActive_clearsRegistration() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ANALYSIS, "Analyse");
        service.registerActive("analysis:ws-1", job.jobId);
        service.unregisterActive("analysis:ws-1", job.jobId);
        assertNull(service.activeJob("analysis:ws-1"));
    }

    @Test
    void stageMessages_perKind() {
        assertEquals("Ihre Anfrage wird analysiert …",
                ProgressStageMessages.forKind(JobProgressService.Kind.ASSISTANT, "intent"));
        assertEquals("Die Empfehlung wird erstellt …",
                ProgressStageMessages.forKind(JobProgressService.Kind.ANALYSIS, "answer-generation"));
        assertEquals("Die Auswertung wird unabhängig geprüft …",
                ProgressStageMessages.forKind(JobProgressService.Kind.EVALUATION, "ground"));
        assertNull(ProgressStageMessages.forKind(JobProgressService.Kind.ASSISTANT, "unknown-stage"));
    }

    @Test
    void cleanupExpired_neverRemovesLongRunningJob() {
        // A legitimate long-running job (22+ min observed in the VM
        // verification) must never expire merely because of its age.
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage",
                java.time.Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));
        service.cleanupExpired();
        assertSame(job, service.get(job.jobId));
        assertEquals("RUNNING", job.state);
    }

    @Test
    void cleanupExpired_keepsTerminalJobWithinTtl() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage");
        service.complete(job.jobId, "ergebnis", "Fertig");
        service.cleanupExpired();
        assertSame(job, service.get(job.jobId));
    }

    @Test
    void cleanupExpired_removesDoneJobAfterTtl() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage",
                java.time.Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));
        service.complete(job.jobId, "ergebnis", "Fertig");
        service.cleanupExpired();
        assertNull(service.get(job.jobId));
    }

    @Test
    void cleanupExpired_removesErrorJobAfterTtl() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage",
                java.time.Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));
        service.fail(job.jobId);
        service.cleanupExpired();
        assertNull(service.get(job.jobId));
    }

    @Test
    void cleanupExpired_appliesTtlAfterJobReachesTerminalState() {
        JobProgressService.Job job = service.create(JobProgressService.Kind.ASSISTANT, "Frage",
                java.time.Instant.now().minus(20, java.time.temporal.ChronoUnit.MINUTES));
        service.cleanupExpired();
        assertSame(job, service.get(job.jobId), "running job must survive");

        service.complete(job.jobId, "ergebnis", "Fertig");
        service.cleanupExpired();
        assertNull(service.get(job.jobId), "terminal job older than TTL must be removed");
    }
}
