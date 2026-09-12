package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.controller.AssistantController.PipelineNode;
import verwaltungsassistent.web.service.JobProgressService;
import verwaltungsassistent.web.service.JobProgressService.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused tests for the interactive assistant-pipeline visualization:
 * node order/states follow the real runtime stage stream, runtime values
 * (sources, evidence items, grounding result) are exposed, and the progress
 * panel renders the diagram with them. Existing answer behavior is untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AssistantPipelineVisualizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobProgressService progressService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER")),
                        null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nodesFollowRealStageOrder_andExposeRuntimeValues() {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Test");
        job.stages.addAll(List.of("intent", "retrieval-done"));
        job.stageData.put("retrieval-done", Map.of("sources", 25, "authorities", 2));
        job.stageData.put("evidence", Map.of("evidenceItems", 4));
        job.stageData.put("ground", Map.of("grounded", false, "confidence", 0, "unsupportedFindings", 2));

        List<PipelineNode> nodes = AssistantController.pipelineNodes(job);

        assertEquals(List.of("frage", "intent", "retrieval", "evidence", "ground", "coverage", "antwort"),
                nodes.stream().map(PipelineNode::id).toList(), "node order is fixed");
        assertEquals("done", nodes.get(0).state());   // Frage
        assertEquals("done", nodes.get(1).state());   // Query Intention
        assertEquals("active", nodes.get(2).state()); // Retrieval = last reached stage
        assertEquals("pending", nodes.get(3).state()); // Evidence Anchor not reached
        assertEquals("pending", nodes.get(6).state()); // Antwort pending while running
        // real runtime values are exposed, not fabricated
        assertTrue(nodes.get(2).info().contains("25"), "retrieval info shows the real source count: " + nodes.get(2).info());
        assertTrue(nodes.get(3).info().contains("4"), "evidence info shows the real evidence count");
        assertTrue(nodes.get(4).info().contains("nicht durch Belege gedeckt"), "grounding info reports unsupported points");
    }

    @Test
    void emptyJob_rendersAllPendingExceptFrage() {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Test");
        List<PipelineNode> nodes = AssistantController.pipelineNodes(job);
        assertEquals("done", nodes.get(0).state());
        for (int i = 1; i < nodes.size(); i++) {
            assertEquals("pending", nodes.get(i).state(), nodes.get(i).id() + " must be pending");
        }
    }

    @Test
    void progressPanel_rendersDiagramWithRuntimeInfo() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Test");
        job.stages.addAll(List.of("intent", "retrieval-done"));
        job.stageData.put("retrieval-done", Map.of("sources", 25));
        job.stageData.put("evidence", Map.of("evidenceItems", 4));

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pipeline-diagram")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pipeline-node--active")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("25 Quellen abgerufen")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("4 Belege")));
    }

    @Test
    void failedJob_rendersErrorStateWithoutDiagram() throws Exception {
        Job job = progressService.create(JobProgressService.Kind.ASSISTANT, "Test");
        progressService.fail(job.jobId);

        mockMvc.perform(get("/assistant/progress/" + job.jobId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Die Anfrage konnte nicht vollständig verarbeitet werden")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("pipeline-diagram"))));
    }
}
