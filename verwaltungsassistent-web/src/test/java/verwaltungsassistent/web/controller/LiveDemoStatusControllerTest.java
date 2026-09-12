package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import verwaltungsassistent.web.service.LiveDemoStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live-demo banner rendering across the three load levels. Users are created
 * through real form logins (mocked AuthFacade), so the banner counts what the
 * actual Spring Security session registry sees.
 */
@SpringBootTest(properties = {
        "app.live-demo.enabled=true",
        "app.live-demo.moderate-threshold=5",
        "app.live-demo.high-threshold=10"
})
@AutoConfigureMockMvc
class LiveDemoStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionRegistry sessionRegistry;

    @MockBean
    private AuthFacade authFacade;

    @SpyBean
    private LiveDemoStatusService statusService;

    private final List<String> registeredSessionIds = new ArrayList<>();

    @AfterEach
    void cleanSessions() {
        registeredSessionIds.forEach(sessionRegistry::removeSessionInformation);
        registeredSessionIds.clear();
    }

    private MockHttpSession loginAs(String email) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/login")
                        .user("email", email)
                        .password("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        registeredSessionIds.add(session.getId());
        return session;
    }

    private void mockUser(String email) {
        when(authFacade.currentUser(email)).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), email, "Teilnehmer", Set.of("USER")));
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpAuthMock() {
        when(authFacade.login(any(LoginCommand.class))).thenReturn(null);
        when(authFacade.currentUser(anyString())).thenAnswer(invocation ->
                new AuthenticatedUser(UUID.randomUUID(), invocation.getArgument(0),
                        "Teilnehmer", Set.of("USER")));
    }

    private String dashboardWith(int participantCount) throws Exception {
        MockHttpSession session = null;
        for (int i = 0; i < participantCount; i++) {
            // Each participant logs in and loads a page (real activity);
            // the login request itself does not touch (the security chain
            // stops at the authentication filter after a successful login).
            session = loginAs("user" + i + "@demo.local");
            mockMvc.perform(get("/dashboard").session(session))
                    .andExpect(status().isOk());
        }
        return mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void greenState_showsCountAndGreenCopy() throws Exception {
        String html = dashboardWith(3);

        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("LIVE-DEMO"));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("· 3 Nutzer aktiv"));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("Derzeit nutzen mehrere Teilnehmer gleichzeitig die AI Engine."));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("status-dot--green"));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("live-demo-banner--green"));
    }

    @Test
    void yellowState_showsCountAndSimultaneousUsageCopy() throws Exception {
        String html = dashboardWith(9);

        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("· 9 Nutzer aktiv"));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("Da die Demo während der Veranstaltung von mehreren Teilnehmern gleichzeitig genutzt wird, kann es zeitweise zu längeren Antwortzeiten kommen."));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("status-dot--amber"));
    }

    @Test
    void redState_hidesCountAndShowsLoadCopy() throws Exception {
        String html = dashboardWith(12);

        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("· derzeit stark ausgelastet"));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("Viele Teilnehmer nutzen die AI Engine gleichzeitig. Dadurch kann es momentan zu längeren Antwortzeiten kommen."));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("status-dot--red"));
        // No count and no capacity/maximum exposure in the red state.
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Nutzer aktiv")));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(" / 12")));
    }

    @Test
    void banner_refreshesDynamicallyViaHtmxPolling() throws Exception {
        String html = dashboardWith(1);

        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("hx-get=\"/live-demo/status\""));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("hx-trigger=\"every 60s\""));
        org.hamcrest.MatcherAssert.assertThat(html,
                org.hamcrest.Matchers.containsString("hx-swap=\"outerHTML\""));
    }

    @Test
    void statusFragmentEndpoint_rendersBanner() throws Exception {
        MockHttpSession session = loginAs("frag@demo.local");

        mockMvc.perform(get("/live-demo/status").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LIVE-DEMO")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nutzer aktiv")));
    }

    @Test
    void unauthenticatedVisitor_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/live-demo/status"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void realPageRequest_recordsActivity() throws Exception {
        MockHttpSession session = loginAs("touch@demo.local");
        Mockito.clearInvocations(statusService);

        mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk());

        verify(statusService, atLeastOnce()).touch("touch@demo.local");
    }

    @Test
    void statusPoll_doesNotRecordActivity() throws Exception {
        MockHttpSession session = loginAs("poll@demo.local");
        Mockito.clearInvocations(statusService);

        mockMvc.perform(get("/live-demo/status").session(session))
                .andExpect(status().isOk());

        verify(statusService, never()).touch(anyString());
    }
}
