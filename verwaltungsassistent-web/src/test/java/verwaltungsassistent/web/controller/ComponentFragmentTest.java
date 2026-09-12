package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ComponentFragmentTest {

    @Autowired
    private MockMvc mockMvc;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "test@example.com", "Test User", Set.of("USER"));

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- emptyState ---

    @Test
    void emptyState_simple_rendersCorrectly() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Aktivität")))
                .andExpect(content().string(containsString("role=\"status\"")))
                .andExpect(content().string(containsString("aria-live=\"polite\"")));
    }

    @Test
    void emptyState_cta_rendersWithActionButton() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Keine Fälle")))
                .andExpect(content().string(containsString("Ersten Fall erstellen")));
    }

    @Test
    void emptyState_search_rendersWithResetLink() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Filter zurücksetzen")));
    }

    // --- badge ---

    @Test
    void badge_rendersAllVariants() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("badge--success")))
                .andExpect(content().string(containsString("badge--warning")))
                .andExpect(content().string(containsString("badge--error")))
                .andExpect(content().string(containsString("badge--info")))
                .andExpect(content().string(containsString("badge--neutral")));
    }

    // --- pagination ---

    @Test
    void pagination_rendersWithCorrectAriaAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Pagination\"")))
                .andExpect(content().string(containsString("aria-current=\"page\"")));
    }

    @Test
    void pagination_hasHtmxAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-get")))
                .andExpect(content().string(containsString("hx-target")))
                .andExpect(content().string(containsString("hx-swap=\"outerHTML\"")))
                .andExpect(content().string(containsString("hx-push-url=\"true\"")));
    }

    @Test
    void pagination_showsCorrectPageInfo() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Seite")))
                .andExpect(content().string(containsString("von")));
    }

    // --- filterBar ---

    @Test
    void filterBar_rendersWithCorrectAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Filter\"")))
                .andExpect(content().string(containsString("hx-trigger=\"change\"")))
                .andExpect(content().string(containsString("hx-push-url=\"true\"")));
    }

    @Test
    void filterBar_rendersSelectAndTextInputs() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("filter-bar__select")))
                .andExpect(content().string(containsString("type=\"text\"")));
    }

    // --- dataTable ---

    @Test
    void dataTable_rendersWithAccessibilityAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"grid\"")))
                .andExpect(content().string(containsString("role=\"columnheader\"")))
                .andExpect(content().string(containsString("scope=\"col\"")));
    }

    @Test
    void dataTable_rendersSortableHeadersWithAriaSort() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-sort=\"none\"")))
                .andExpect(content().string(containsString("data-table__header--sortable")));
    }

    @Test
    void dataTable_rendersSampleData() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall Müller")))
                .andExpect(content().string(containsString("Fall Schmidt")))
                .andExpect(content().string(containsString("Fall Weber")));
    }

    @Test
    void dataTable_hasContainerWithId() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"showcase-table\"")));
    }

    // --- modal ---

    @Test
    void modal_rendersWithAccessibilityAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"dialog\"")))
                .andExpect(content().string(containsString("aria-modal=\"true\"")))
                .andExpect(content().string(containsString("aria-labelledby")));
    }

    @Test
    void modal_rendersWithAlpineJsDirectives() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("x-data")))
                .andExpect(content().string(containsString("x-show")))
                .andExpect(content().string(containsString("x-trap")));
    }

    @Test
    void modal_hasCloseButton() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Schließen\"")));
    }

    @Test
    void modal_opensViaAlpineJs() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@click=\"demoModalOpen = true\"")));
    }

    // --- confirmDelete ---

    @Test
    void confirmDelete_rendersWithCorrectStructure() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Löschen bestätigen")))
                .andExpect(content().string(containsString("Abbrechen")))
                .andExpect(content().string(containsString("Löschen")));
    }

    @Test
    void confirmDelete_hasHtmxDeleteAttribute() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-delete")));
    }

    @Test
    void confirmDelete_showsItemName() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fall Müller")));
    }

    // --- tabBar ---

    @Test
    void tabBar_rendersWithAccessibilityAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"tablist\"")))
                .andExpect(content().string(containsString("role=\"tab\"")))
                .andExpect(content().string(containsString("aria-selected=\"true\"")))
                .andExpect(content().string(containsString("role=\"tabpanel\"")));
    }

    @Test
    void tabBar_rendersAllTabs() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Übersicht")))
                .andExpect(content().string(containsString("Dokumente")))
                .andExpect(content().string(containsString("Timeline")))
                .andExpect(content().string(containsString("Checkliste")))
                .andExpect(content().string(containsString("Notizen")));
    }

    @Test
    void tabBar_usesAlpineJsForToggling() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("x-data")))
                .andExpect(content().string(containsString("x-show=\"activeTab")));
    }

    // --- loading spinner ---

    @Test
    void spinner_rendersWithAriaAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-busy=\"true\"")));
    }

    // --- info message ---

    @Test
    void infoMessage_rendersWithCorrectClass() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("message--info")))
                .andExpect(content().string(containsString("role=\"status\"")));
    }

    // --- loading skeleton ---

    @Test
    void skeleton_rendersStructure() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loading-skeleton")))
                .andExpect(content().string(containsString("aria-busy=\"true\"")));
    }

    // --- Showcase page structure ---

    @Test
    void showcase_pageLoadsSuccessfully() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(view().name("dev/showcase"))
                .andExpect(model().attributeExists("tableHeaders"))
                .andExpect(model().attributeExists("tableRows"))
                .andExpect(model().attributeExists("sampleFilters"))
                .andExpect(model().attributeExists("sampleTabs"))
                .andExpect(model().attributeExists("phaseStages"))
                .andExpect(model().attributeExists("phaseCurrentIndex"));
    }

    @Test
    void showcase_pageHasAllSections() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Badge")))
                .andExpect(content().string(containsString("Empty State")))
                .andExpect(content().string(containsString("Filter Bar")))
                .andExpect(content().string(containsString("Search Box")))
                .andExpect(content().string(containsString("Data Table")))
                .andExpect(content().string(containsString("Pagination")))
                .andExpect(content().string(containsString("Modal Dialog")))
                .andExpect(content().string(containsString("Confirm Delete")))
                .andExpect(content().string(containsString("Tab Bar")))
                .andExpect(content().string(containsString("Validation")))
                .andExpect(content().string(containsString("Phase Progress")))
                .andExpect(content().string(containsString("Loading Spinner")))
                .andExpect(content().string(containsString("Info Message")))
                .andExpect(content().string(containsString("Loading Skeleton")));
    }

    // --- searchBox ---

    @Test
    void searchBox_rendersWithHtmxAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-trigger=\"keyup changed delay:300ms\"")))
                .andExpect(content().string(containsString("hx-push-url=\"true\"")));
    }

    @Test
    void searchBox_rendersWithCorrectInputType() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("type=\"search\"")))
                .andExpect(content().string(containsString("placeholder=\"Fälle durchsuchen...\"")));
    }

    // --- validation ---

    @Test
    void fieldErrors_usesCorrectCssClass() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("form-field__error")))
                .andExpect(content().string(containsString("role=\"alert\"")));
    }

    @Test
    void globalErrors_usesMessageErrorClass() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("message--error")));
    }

    // --- phaseProgress ---

    @Test
    void phaseProgress_rendersAllStages() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Einrichtung")))
                .andExpect(content().string(containsString("Ingestion")))
                .andExpect(content().string(containsString("Analyse")))
                .andExpect(content().string(containsString("Überprüfung")))
                .andExpect(content().string(containsString("Abschluss")));
    }

    @Test
    void phaseProgress_hasAccessibilityAttributes() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"progressbar\"")))
                .andExpect(content().string(containsString("aria-valuenow")))
                .andExpect(content().string(containsString("aria-valuemin")))
                .andExpect(content().string(containsString("aria-valuemax")));
    }

    @Test
    void phaseProgress_rendersCompleteAndCurrentStates() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("phase-progress__segment--complete")))
                .andExpect(content().string(containsString("phase-progress__segment--current")))
                .andExpect(content().string(containsString("phase-progress__segment--remaining")));
    }

    @Test
    void phaseProgress_showsStageLabel() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Phase")))
                .andExpect(content().string(containsString("von 5")));
    }

    // --- skeleton fragment ---

    @Test
    void skeletonFragment_rendersWithCorrectClass() throws Exception {
        mockMvc.perform(get("/dev/showcase"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loading-skeleton")))
                .andExpect(content().string(containsString("aria-busy=\"true\"")))
                .andExpect(content().string(containsString("loading-skeleton__line")));
    }
}
