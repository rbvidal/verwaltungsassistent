package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the Fälle status-filter layout bug: the sidebar filter
 * links must swap the whole list container (outerHTML) instead of nesting the
 * layout inside itself, which collapsed the result column to the sidebar width.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CasesLayoutRegressionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkspaceService workspaceService;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(workspaceService.findByOwner(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fullPage_sidebarLinksUseOuterHtmlSwap() throws Exception {
        String html = mockMvc.perform(get("/cases"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int outerSwaps = count(html, "hx-swap=\"outerHTML\"");
        assertTrue(outerSwaps >= 6,
                "expected sidebar links (5) and search box to use outerHTML, found " + outerSwaps);
        assertEquals(1, count(html, "id=\"case-list-container\""),
                "the list container must appear exactly once");
    }

    @Test
    void statusFilterResponse_containsSingleUnnestedContainer() throws Exception {
        String html = mockMvc.perform(get("/cases").param("status", "ACTIVE")
                        .header("HX-Request", "true").header("HX-Target", "case-list-container"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, count(html, "id=\"case-list-container\""),
                "the swapped fragment must contain exactly one container (no nesting)");
        assertTrue(count(html, "hx-swap=\"outerHTML\"") >= 5,
                "all five sidebar links must swap outerHTML");
        assertTrue(html.contains("cases-sidebar__filter--active"),
                "the active filter must be marked in the re-rendered sidebar");
    }

    @Test
    void alleFilter_returnsFullListLayout() throws Exception {
        String html = mockMvc.perform(get("/cases")
                        .header("HX-Request", "true").header("HX-Target", "case-list-container"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, count(html, "id=\"case-list-container\""),
                "the 'Alle' response must contain the full layout, not only the table");
        assertTrue(html.contains("class=\"cases-main\""),
                "the 'Alle' response must contain the result column");
    }

    @Test
    void everyStatusFilterRendersUsableResultArea() throws Exception {
        for (String status : List.of("ACTIVE", "DRAFT", "CLOSED", "ARCHIVED")) {
            String html = mockMvc.perform(get("/cases").param("status", status)
                            .header("HX-Request", "true").header("HX-Target", "case-list-container"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertTrue(html.contains("class=\"cases-main\""),
                    "result column must be present for status " + status);
            assertTrue(html.contains("Keine Fälle") || html.contains("cases-table"),
                    "result area must render for status " + status);
            assertEquals(1, count(html, "id=\"case-list-container\""),
                    "no nesting for status " + status);
        }
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
