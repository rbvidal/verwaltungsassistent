package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.DashboardService;
import verwaltungsassistent.web.viewmodel.DashboardViewModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(dashboardService.build(any(AuthenticatedUser.class)))
                .thenReturn(new DashboardViewModel(
                        "Test User", List.of("USER"), 10, 5, 2, 1, 3, 0,
                        List.of(new DashboardViewModel.RecentActivity(
                                java.time.Instant.now(), "USER_LOGIN", "USER", "123", "USER", null)),
                        List.of(),
                        List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dashboard_authenticated_returnsDashboardPage() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"))
                .andExpect(model().attributeExists("dashboard"))
                .andExpect(model().attributeExists("breadcrumbs"))
                .andExpect(model().attribute("activeSection", "dashboard"));
    }

    @Test
    void dashboard_showsUserName() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Test User")));
    }

    @Test
    void dashboard_showsDocumentCounts() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("10")));
    }

    /**
     * Phase 2D.12: Leitungs-Konto (ADMIN, read-only) — das Dashboard rendert
     * die Aufsichts-Sicht, den Systemstatus (echte Erreichbarkeits-Prüfung)
     * und die "Letzten Aktivitäten" in verständlicher deutscher Darstellung.
     */
    @Test
    void dashboard_supervisory_showsSupervisionSystemStatusAndActivities() throws Exception {
        var admin = new AuthenticatedUser(
                UUID.randomUUID(), "admin@verwaltungsassistent.local", "Leitung", Set.of("ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        when(dashboardService.build(any(AuthenticatedUser.class)))
                .thenReturn(new DashboardViewModel(
                        "Leitung", List.of("ADMIN"), 0, 0, 0, 0, 0, 0,
                        List.of(new DashboardViewModel.RecentActivity(
                                java.time.Instant.now(), "Dokument geöffnet",
                                "DOCUMENT", "11111111-2222-3333-4444-555555555555",
                                "Dokument", "Mietvertrag.pdf")),
                        List.of(), List.of()));
        when(dashboardService.supervisionView(any(AuthenticatedUser.class)))
                .thenReturn(new verwaltungsassistent.web.service.DashboardService.SupervisionView(
                        1, 1, 0, 0, 3, 0,
                        List.of(), List.of(), List.of(),
                        "Leitungs-Übersicht: 1 offener Vorgang"));

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Leitungs-Übersicht")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SYSTEMSTATUS")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Letzte Aktivitäten")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mietvertrag.pdf")));
    }

    @Test
    void rootUrl_returnsDashboard() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void dashboard_unauthenticated_redirectsToLogin() throws Exception {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }
}
