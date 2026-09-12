package verwaltungsassistent.web.controller;

import reasoning.ai.api.AiFacade;
import reasoning.ai.application.DecisionRouter;
import reasoning.ai.model.AiRequest;
import reasoning.ai.model.AiResponse;
import reasoning.ai.model.ConfidenceProfile;
import reasoning.ai.model.InferenceMetadata;
import reasoning.ai.model.ReasonedAnswer;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.workspace.api.WorkspaceDto;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.service.JobProgressService;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decision result must never present synthetic legal references such as
 * "Entry 535 / Reference text for entry 535". With no genuine authority the
 * page shows the honest empty state instead, and the document metric is
 * labeled distinctly ("Quellenrelevanz").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DecisionAuthorityEmptyStateTest {

    private static final String CASE_ID = UUID.nameUUIDFromBytes("ws-1".getBytes()).toString();

    private static final Pattern JOB_URL =
            Pattern.compile("/cases/" + CASE_ID + "/decision/analyze/progress/([a-f0-9-]+)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @MockBean
    private WorkspaceService workspaceService;

    @MockBean
    private AiFacade aiFacade;

    @MockBean
    private DecisionRouter decisionRouter;

    private final AuthenticatedUser testUser = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));

    private final WorkspaceEntity case1 = createCaseEntity();

    private static WorkspaceEntity createCaseEntity() {
        WorkspaceEntity e = new WorkspaceEntity("FALL-001", "Fall Müller", "Testfall", "DECISION", "user@example.com");
        e.setId(UUID.nameUUIDFromBytes(CASE_ID.getBytes()).toString());
        e.setStatus(reasoning.common.model.WorkspaceStatus.ACTIVE);
        return e;
    }

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken(
                testUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(workspaceService.findById(anyString())).thenReturn(Optional.empty());
        when(workspaceService.findById(CASE_ID)).thenReturn(Optional.of(case1));
        when(workspaceService.toDto(any(WorkspaceEntity.class))).thenAnswer(inv -> {
            WorkspaceEntity e = inv.getArgument(0);
            return new WorkspaceDto(
                    e.getId(), e.getWorkspaceCode(), e.getName(),
                    e.getDescription(), e.getWorkspaceType(), e.getStatus(), e.getPhase(),
                    e.getOwnerId(), Map.of(), List.of(), List.of(),
                    e.getCreatedAt(), e.getUpdatedAt());
        });
        when(decisionRouter.route(anyString())).thenReturn(null);

        var answer = new ReasonedAnswer(
                "Für diese Frage liegen in der Wissensbasis keine ausreichenden Informationen vor.",
                List.of(), List.of(), null, null,
                new ConfidenceProfile(0.0, 0.0, 0.0, 0.0, 0.0, "test"),
                false, 0.0, false);
        var metadata = new InferenceMetadata(
                "test", "test-model", Instant.now(), Instant.now(),
                null, null, null, "HYBRID", List.of(), 0.0);
        when(aiFacade.answer(any(AiRequest.class)))
                .thenReturn(new AiResponse(answer, metadata));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void decisionResult_withoutAuthorities_showsHonestEmptyState() throws Exception {
        String startHtml = mockMvc.perform(post("/cases/" + CASE_ID + "/decision/analyze").with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher m = JOB_URL.matcher(startHtml);
        assertThat("progress URL must be present", m.find());

        String result = awaitResult(m.group(1));

        assertThat(result, containsString("Keine spezifische Rechtsgrundlage ermittelt."));
        assertThat(result, not(containsString("Entry 535")));
        assertThat(result, not(containsString("Reference text for entry")));
        assertThat(result, not(containsString("30%")));
    }

    private String awaitResult(String jobId) throws Exception {
        for (int i = 0; i < 50; i++) {
            JobProgressService.Job job = progressService.get(jobId);
            if (job != null && "DONE".equals(job.state)) {
                return mockMvc.perform(get("/cases/" + CASE_ID + "/decision/analyze/progress/" + jobId))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Analysis job did not complete");
    }
}
