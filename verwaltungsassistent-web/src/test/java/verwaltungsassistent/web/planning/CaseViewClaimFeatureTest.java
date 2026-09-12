package verwaltungsassistent.web.planning;

import verwaltungsassistent.web.planning.persistence.CaseViewClaimEntity;
import verwaltungsassistent.web.planning.persistence.JpaCaseViewClaimRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.security.WorkspaceVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arbeitspool View-Lease (A–H): atomarer Anspruch, Sichtbarkeit, Freigabe,
 * Verfall, Überführung in Zuweisung beim Pipeline-Start und Konkurrenz.
 * Anwendungsschicht (echte Services/Guard), kein Mock der Claim-Logik.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaseViewClaimFeatureTest {

    private static final String A = "demo02@verwaltungsassistent.local";
    private static final String B = "demo03@verwaltungsassistent.local";

    @Autowired
    private CaseViewClaimService claimService;
    @Autowired
    private JpaCaseViewClaimRepository claimRepository;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private CaseAccessGuard guard;
    @Autowired
    private CaseAssignmentService assignmentService;

    private WorkspaceEntity freePoolCase(String label) {
        return workspaceService.createWorkspace(new CreateWorkspaceCommand(
                label + " – " + UUID.randomUUID().toString().substring(0, 8),
                "Arbeitspool-Testfall", "CASE", null));
    }

    private AuthenticatedUser user(String email) {
        return new AuthenticatedUser(UUID.randomUUID(), email, email, java.util.Set.of("USER"));
    }

    // A) Employee A claims a free pool case.
    @Test
    void a_employeeClaimsFreePoolCase() {
        WorkspaceEntity ws = freePoolCase("Claim-A");
        claimService.claim(ws.getId().toString(), A);
        assertTrue(claimRepository.findById(ws.getId().toString()).isPresent());
        assertEquals(A, claimRepository.findById(ws.getId().toString()).orElseThrow().getClaimant());
    }

    // B) Employee B is rejected while A holds the claim.
    @Test
    void b_otherEmployeeIsRejected() {
        WorkspaceEntity ws = freePoolCase("Claim-B");
        claimService.claim(ws.getId().toString(), A);
        assertThrows(CaseViewClaimService.ClaimConflictException.class,
                () -> claimService.claim(ws.getId().toString(), B));
    }

    // B-direct + C) B's list/direct access: case absent from B's list; guard rejects B.
    @Test
    void c_claimedCaseIsAbsentFromOthersListAndDirectAccess() {
        WorkspaceEntity ws = freePoolCase("Claim-C");
        claimService.claim(ws.getId().toString(), A);

        List<WorkspaceEntity> listB = WorkspaceVisibility.ownAndPool(workspaceService, B);
        assertTrue(listB.stream().noneMatch(w -> w.getId().equals(ws.getId())),
                "B's list must not contain the case claimed by A");
        List<WorkspaceEntity> listA = WorkspaceVisibility.ownAndPool(workspaceService, A);
        assertTrue(listA.stream().anyMatch(w -> w.getId().equals(ws.getId())),
                "A's list must still contain the claimed case");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.requireAccess(ws.getId().toString(), user(B)));
        assertEquals(423, ex.getStatusCode().value());
    }

    // D) A releases without starting processing → case free again.
    @Test
    void d_releaseWithoutProcessingFreesTheCase() {
        WorkspaceEntity ws = freePoolCase("Claim-D");
        claimService.claim(ws.getId().toString(), A);
        claimService.release(ws.getId().toString(), A);
        assertFalse(claimRepository.findById(ws.getId().toString()).isPresent());
    }

    // E) After release, B can claim it.
    @Test
    void e_afterReleaseOtherEmployeeCanClaim() {
        WorkspaceEntity ws = freePoolCase("Claim-E");
        claimService.claim(ws.getId().toString(), A);
        claimService.release(ws.getId().toString(), A);
        claimService.claim(ws.getId().toString(), B);
        assertEquals(B, claimRepository.findById(ws.getId().toString()).orElseThrow().getClaimant());
    }

    // F) Pipeline start promotes the claim to a real assignment (A keeps it after leaving).
    @Test
    void f_pipelineStartPromotesToAssignmentAndSurvivesLeave() {
        WorkspaceEntity ws = freePoolCase("Claim-F");
        claimService.claim(ws.getId().toString(), A);
        assertTrue(claimService.promoteIfClaimedBy(ws.getId().toString(), A));
        assignmentService.assign(ws.getId().toString(), A, true);

        WorkspaceEntity after = workspaceService.findById(ws.getId().toString()).orElseThrow();
        assertEquals(A, after.getOwnerId(), "case must be assigned to A after pipeline start");
        assertFalse(claimRepository.findById(ws.getId().toString()).isPresent(),
                "temporary claim is consumed by the assignment");

        // Leaving the page must NOT release the assignment.
        claimService.release(ws.getId().toString(), A);
        after = workspaceService.findById(ws.getId().toString()).orElseThrow();
        assertEquals(A, after.getOwnerId());

        // B cannot take/open it.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> guard.requireAccess(ws.getId().toString(), user(B)));
        assertEquals(403, ex.getStatusCode().value());
    }

    // G) Expired claim becomes available again.
    @Test
    void g_expiredClaimBecomesAvailable() {
        WorkspaceEntity ws = freePoolCase("Claim-G");
        claimService.claim(ws.getId().toString(), A);
        CaseViewClaimEntity claim = claimRepository.findById(ws.getId().toString()).orElseThrow();
        claim.refresh(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        claimRepository.saveAndFlush(claim);

        claimService.sweepExpired();

        claimService.claim(ws.getId().toString(), B); // must succeed now
        assertEquals(B, claimRepository.findById(ws.getId().toString()).orElseThrow().getClaimant());
    }

    // H) Two concurrent claims → exactly one winner.
    @Test
    void h_concurrentClaimsHaveExactlyOneWinner() throws Exception {
        WorkspaceEntity ws = freePoolCase("Claim-H");
        String id = ws.getId().toString();
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread tA = new Thread(() -> {
            try {
                start.await();
                try {
                    claimService.claim(id, A);
                    winners.incrementAndGet();
                } catch (CaseViewClaimService.ClaimConflictException expected) {
                    // loser
                }
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        }, "claim-A");

        Thread tB = new Thread(() -> {
            try {
                start.await();
                try {
                    claimService.claim(id, B);
                    winners.incrementAndGet();
                } catch (CaseViewClaimService.ClaimConflictException expected) {
                    // loser
                }
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        }, "claim-B");

        tA.start();
        tB.start();
        start.countDown();
        done.await();
        assertEquals(1, winners.get(), "exactly one concurrent claim may win");
        assertNotNull(claimRepository.findById(id).orElse(null));
    }
}
