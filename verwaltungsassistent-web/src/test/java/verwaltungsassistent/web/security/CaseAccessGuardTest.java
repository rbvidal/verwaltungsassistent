package verwaltungsassistent.web.security;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Phase 2C.5 — Objekt-Level-Autorisierung: Das Leitungs-Konto (ADMIN) liest
 * alle Vorgänge, aber Schreibversuche werden serverseitig abgewiesen (403);
 * Mitarbeiterinnen behalten ihren bisherigen Zugriff (eigene + Pool).
 */
@ExtendWith(MockitoExtension.class)
class CaseAccessGuardTest {

    @Mock
    private WorkspaceService workspaceService;

    private CaseAccessGuard guard;
    private WorkspaceEntity caseEntity;

    private final String caseId = UUID.randomUUID().toString();
    private final AuthenticatedUser leitung = new AuthenticatedUser(
            UUID.randomUUID(), "admin@verwaltungsassistent.local", "Leitung", Set.of("ADMIN"));
    private final AuthenticatedUser employee = new AuthenticatedUser(
            UUID.randomUUID(), "demo01@verwaltungsassistent.local", "Anna", Set.of("USER"));

    @BeforeEach
    void setUp() {
        guard = new CaseAccessGuard(workspaceService, null);
        caseEntity = new WorkspaceEntity("WS-ABCD1234", "Fall Müller - Wohngeld",
                "Wohngeldantrag", "CASE", "demo01@verwaltungsassistent.local");
        caseEntity.setId(caseId);
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(caseEntity));
    }

    @Test
    void leitung_readsEveryCase() {
        assertEquals(caseEntity, guard.requireAccess(caseId, leitung),
                "Leitungs-Konto liest jeden Vorgang");
    }

    @Test
    void leitung_writeIsForbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.requireWriteAccess(caseId, leitung));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void employee_writesOwnCase() {
        assertEquals(caseEntity, guard.requireWriteAccess(caseId, employee),
                "Mitarbeiterin bearbeitet ihren eigenen Vorgang");
    }

    @Test
    void employee_readsAndWritesPoolCase() {
        caseEntity.setOwnerId(null);
        assertEquals(caseEntity, guard.requireAccess(caseId, employee),
                "Pool-Vorgänge sind für jede Mitarbeiterin sichtbar");
        assertEquals(caseEntity, guard.requireWriteAccess(caseId, employee),
                "Pool-Vorgänge dürfen explizit übernommen werden");
    }

    @Test
    void otherEmployee_cannotWriteForeignCase() {
        AuthenticatedUser other = new AuthenticatedUser(
                UUID.randomUUID(), "demo02@verwaltungsassistent.local", "Ben", Set.of("USER"));
        assertThrows(ResponseStatusException.class,
                () -> guard.requireWriteAccess(caseId, other));
    }
}
