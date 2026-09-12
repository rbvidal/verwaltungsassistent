package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.DataBackupService;
import verwaltungsassistent.web.service.DataRestoreService;
import verwaltungsassistent.web.service.JobProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for the "Datenwiederherstellung" administration page: the page
 * is ADMIN-only, restore and delete-all require an explicit confirmation that
 * is re-validated on the backend, invalid/missing dump files are rejected, and
 * a confirmed valid restore reaches the restore service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AdminDataRestoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private DataRestoreService dataRestoreService;

    @MockBean
    private DataBackupService dataBackupService;

    private static final byte[] PGDMP = {'P', 'G', 'D', 'M', 'P', 0, 1, 2, 3, 4, 5};

    private void login(String email, Set<String> roles) {
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(), email, "Test", roles),
                null, roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @BeforeEach
    void setUp() {
        when(dataRestoreService.restorePostgres(any())).thenReturn(
                new DataRestoreService.RestoreOutcome("PostgreSQL", true, "Wiederhergestellt"));
        when(dataRestoreService.restoreNeo4j(any())).thenReturn(
                new DataRestoreService.RestoreOutcome("Neo4j", true, "Wiederhergestellt"));
        when(dataRestoreService.deleteAllIndexData()).thenReturn(
                new DataRestoreService.DeleteAllOutcome(true, true, true, true, 2, 42));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    @Test
    void nonAdmin_cannotAccessPage() throws Exception {
        login("user@example.com", Set.of("USER"));
        mockMvc.perform(get("/admin/data-restore"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdmin_cannotRestore() throws Exception {
        login("user@example.com", Set.of("USER"));
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(pgDump()).file(neo4jDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(dataRestoreService, never()).restorePostgres(any());
    }

    @Test
    void nonAdmin_cannotDeleteAll() throws Exception {
        login("user@example.com", Set.of("USER"));
        mockMvc.perform(multipart("/admin/data-restore/delete-all")
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(dataRestoreService, never()).deleteAllIndexData();
    }

    @Test
    void plainAdmin_withoutSuperadmin_cannotAccessRestore() throws Exception {
        // Phase 2D.12: das normale ADM/Leitungs-Konto (rein aufsichtlich) darf
        // die destruktive Datenwiederherstellung weder öffnen noch ausführen —
        // nur das versteckte SUPERADMIN-Konto (serverseitig abgewiesen).
        login("leitung@example.com", Set.of("ADMIN"));
        mockMvc.perform(get("/admin/data-restore"))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/admin/data-restore/delete-all")
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(dataRestoreService, never()).deleteAllIndexData();
    }

    @Test
    void admin_canOpenPage() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        when(dataBackupService.list()).thenReturn(List.of(
                new DataBackupService.BackupEntry("PostgreSQL", "postgres-20260821-1612.dump",
                        "21.08.2026 16:12", 42_000_000, "Erfolgreich", "20260821-1612",
                        "C:\\backups\\postgres-20260821-1612.dump")));
        mockMvc.perform(get("/admin/data-restore"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Datenwiederherstellung")))
                .andExpect(content().string(containsString("Datensicherung")))
                .andExpect(content().string(containsString("Datenbank sichern")))
                .andExpect(content().string(containsString("Neo4j sichern")))
                .andExpect(content().string(containsString("Qdrant sichern")))
                .andExpect(content().string(containsString("Alle Daten sichern")))
                .andExpect(content().string(containsString("Vorhandene Sicherungen")))
                .andExpect(content().string(containsString("postgres-20260821-1612.dump")))
                .andExpect(content().string(containsString("Daten wiederherstellen")))
                .andExpect(content().string(containsString("Alle Daten löschen")))
                .andExpect(content().string(containsString("Ich habe verstanden")));
    }

    // ── Backup authorization + flow ───────────────────────────────────────────

    @Test
    void nonAdmin_cannotBackup() throws Exception {
        login("user@example.com", Set.of("USER"));
        mockMvc.perform(post("/admin/data-restore/backup/postgres").with(csrf()))
                .andExpect(status().isForbidden());
        verify(dataBackupService, never()).backupPostgres();
    }

    @Test
    void admin_backupPostgres_startsProgressJob() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(post("/admin/data-restore/backup/postgres").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("/admin/data-restore/backup/progress/")));
    }

    @Test
    void admin_backupUnknownComponent_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(post("/admin/data-restore/backup/unknown").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unbekannte Sicherungs-Komponente")));
    }

    @Test
    void admin_canDownloadBackup() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        java.nio.file.Path backupFile = java.nio.file.Files.createTempFile("postgres-20260821-1612", ".dump");
        java.nio.file.Files.writeString(backupFile, "PGDMP-test");
        when(dataBackupService.resolveBackup("postgres-20260821-1612.dump")).thenReturn(backupFile);
        mockMvc.perform(get("/admin/data-restore/backup/download")
                        .param("file", "postgres-20260821-1612.dump"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PGDMP-test")));
        // invalid file names are rejected before any filesystem access
        mockMvc.perform(get("/admin/data-restore/backup/download")
                        .param("file", "evil.dump"))
                .andExpect(status().isBadRequest());
        // valid name but not present in the backup directory -> 404
        when(dataBackupService.resolveBackup("postgres-20260821-1612.dump")).thenReturn(null);
        mockMvc.perform(get("/admin/data-restore/backup/download")
                        .param("file", "postgres-20260821-1612.dump"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdmin_cannotDownloadBackup() throws Exception {
        login("user@example.com", Set.of("USER"));
        mockMvc.perform(get("/admin/data-restore/backup/download")
                        .param("file", "postgres-20260821-1612.dump"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_backupProgress_running_rendersWithoutError() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        var job = progressService.create(JobProgressService.Kind.DATA_RESTORE, "Datensicherung");

        mockMvc.perform(get("/admin/data-restore/backup/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"progress-panel\"")))
                .andExpect(content().string(containsString("Datensicherung")))
                .andExpect(content().string(not(containsString("java.lang.NullPointerException"))));
    }

    @Test
    void admin_backupProgress_done_rendersResultWithHistory() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        var job = progressService.create(JobProgressService.Kind.DATA_RESTORE, "Datensicherung");
        String rendered = "<div>BACKUP-MARKER</div>";
        progressService.complete(job.jobId, rendered, "abgeschlossen");
        when(dataBackupService.list()).thenReturn(List.of(
                new DataBackupService.BackupEntry("Neo4j", "neo4j-20260821-1613.dump",
                        "21.08.2026 16:13", 18_000_000, "Erfolgreich", "20260821-1613",
                        "/backups/neo4j-20260821-1613.dump")));

        mockMvc.perform(get("/admin/data-restore/backup/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BACKUP-MARKER")));
    }

    // ── Restore validation ────────────────────────────────────────────────────

    @Test
    void restore_withoutConfirmation_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(pgDump()).file(neo4jDump())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte bestätigen Sie")));
        verify(dataRestoreService, never()).restorePostgres(any());
    }

    @Test
    void restore_missingPostgresDump_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(neo4jDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "beide Sicherungsdateien (PostgreSQL-Dump und Neo4j-Dump)")));
        verify(dataRestoreService, never()).restorePostgres(any());
    }

    @Test
    void restore_missingNeo4jDump_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(pgDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "beide Sicherungsdateien (PostgreSQL-Dump und Neo4j-Dump)")));
        verify(dataRestoreService, never()).restorePostgres(any());
    }

    @Test
    void restore_invalidPostgresFile_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        MockMultipartFile notADump = new MockMultipartFile("pgDump", "backup.dump",
                "application/octet-stream", "this is not a postgres dump".getBytes());
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(notADump).file(neo4jDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("kein gültiger pg_dump")));
        verify(dataRestoreService, never()).restorePostgres(any());
    }

    @Test
    void restore_confirmedWithValidFiles_reachesTheService() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(pgDump()).file(neo4jDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Daten werden wiederhergestellt")));

        waitForAsync();
        ArgumentCaptor<Path> pgCaptor = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<Path> neo4jCaptor = ArgumentCaptor.forClass(Path.class);
        verify(dataRestoreService).restorePostgres(pgCaptor.capture());
        verify(dataRestoreService).restoreNeo4j(neo4jCaptor.capture());
    }

    // ── Delete-all validation ─────────────────────────────────────────────────

    @Test
    void deleteAll_withoutConfirmation_isRejected() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/delete-all")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bitte bestätigen Sie")));
        verify(dataRestoreService, never()).deleteAllIndexData();
    }

    @Test
    void deleteAll_confirmed_reachesTheService() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        mockMvc.perform(multipart("/admin/data-restore/delete-all")
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alle Daten werden gelöscht")));

        waitForAsync();
        verify(dataRestoreService).deleteAllIndexData();
    }

    // ── Result rendering ──────────────────────────────────────────────────────

    @Test
    void restoreResult_reportsPartialFailureHonestly() throws Exception {
        login("admin@example.com", Set.of("ADMIN", "SUPERADMIN"));
        when(dataRestoreService.restorePostgres(any())).thenReturn(
                new DataRestoreService.RestoreOutcome("PostgreSQL", true, "Wiederhergestellt"));
        when(dataRestoreService.restoreNeo4j(any())).thenReturn(
                new DataRestoreService.RestoreOutcome("Neo4j", false, "Wiederherstellung fehlgeschlagen: kaputt"));

        String panel = mockMvc.perform(multipart("/admin/data-restore/restore")
                        .file(pgDump()).file(neo4jDump())
                        .param("confirmed", "on")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        waitForAsync();
        String jobId = panel.replaceAll("(?s).*restore/progress/([a-f0-9-]+).*", "$1");
        mockMvc.perform(get("/admin/data-restore/restore/progress/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Wiederhergestellt")))
                .andExpect(content().string(containsString("Wiederherstellung fehlgeschlagen")))
                .andExpect(content().string(containsString(
                        "Die Wiederherstellung wurde nicht vollständig abgeschlossen")));
    }

    private MockMultipartFile pgDump() {
        return new MockMultipartFile("pgDump", "va-postgres.dump",
                "application/octet-stream", PGDMP);
    }

    private MockMultipartFile neo4jDump() {
        return new MockMultipartFile("neo4jDump", "neo4j.dump",
                "application/octet-stream", "neo4j dump bytes".getBytes());
    }

    private static void waitForAsync() throws InterruptedException {
        Thread.sleep(800);
    }
}
