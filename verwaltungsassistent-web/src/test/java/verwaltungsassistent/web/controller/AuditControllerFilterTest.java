package verwaltungsassistent.web.controller;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditEventPage;
import reasoning.audit.api.AuditQuery;
import reasoning.audit.api.AuditService;
import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit view defaults and server-side filtering: the page must default to
 * the last 3 days and pass explicit date range / user filters to the
 * underlying query (older records are only hidden from the UI, not deleted).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuditControllerFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditService auditService;

    private final AuthenticatedUser auditor = new AuthenticatedUser(
            UUID.randomUUID(), "auditor@example.com", "Auditor", Set.of("AUDITOR"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                auditor, null, List.of(new SimpleGrantedAuthority("ROLE_AUDITOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(auditService.query(any())).thenReturn(new AuditEventPage(List.of(), 0, 25, 0, 0));
    }

    /** A single event so the table (and thus the pagination) renders. */
    private static AuditEventPage pageWith(int totalElements, int totalPages) {
        return new AuditEventPage(List.of(new AuditEvent(
                UUID.randomUUID(), Instant.now(), "auditor@example.com", "tenant",
                reasoning.common.audit.AuditEventType.USER_LOGIN,
                "AUTH_USER", "id", "web", "corr", "req", "/login", "POST",
                "127.0.0.1", null)), 0, 25, totalElements, totalPages);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultView_limitsToLastThreeDays() throws Exception {
        mockMvc.perform(get("/audit"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditService).query(captor.capture());
        AuditQuery query = captor.getValue();

        assertNotNull(query.from(), "default view must filter by from");
        Instant expected = java.time.LocalDate.now().minusDays(3)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        assertEquals(expected, query.from(), "from must be the start of day, 3 days back");
        assertNull(query.to(), "no upper bound in the default view");
        assertNull(query.actorId());
    }

    @Test
    void defaultView_exposesOnlyTheLatestThreePages() throws Exception {
        // 37 pages exist in the underlying data; without a user filter the
        // pagination must still expose only the latest 3 pages.
        when(auditService.query(any())).thenReturn(pageWith(925, 37));

        mockMvc.perform(get("/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 von 3")));
    }

    @Test
    void defaultView_clampsDirectPageParametersIntoTheExposedWindow() throws Exception {
        when(auditService.query(any())).thenReturn(pageWith(925, 37));

        mockMvc.perform(get("/audit").param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3 von 3")))
                .andExpect(content().string(not(containsString("11 von"))));

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditService).query(captor.capture());
        assertEquals(2, captor.getValue().page(), "page must be clamped to the last exposed page");
    }

    @Test
    void filteredView_exposesAllMatchingPages() throws Exception {
        when(auditService.query(any())).thenReturn(pageWith(925, 37));

        mockMvc.perform(get("/audit").param("from", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 von 37")));
    }

    @Test
    void explicitFilters_arePassedToTheQuery() throws Exception {
        mockMvc.perform(get("/audit")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-15")
                        .param("actor", "user@example.com"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditQuery> captor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditService).query(captor.capture());
        AuditQuery query = captor.getValue();

        ZoneId zone = ZoneId.systemDefault();
        assertEquals(java.time.LocalDate.parse("2026-08-01").atStartOfDay(zone).toInstant(), query.from());
        // "to" is exclusive: the whole day of 2026-08-15 is included
        assertEquals(java.time.LocalDate.parse("2026-08-16").atStartOfDay(zone).toInstant(), query.to());
        assertEquals("user@example.com", query.actorId());
    }
}
