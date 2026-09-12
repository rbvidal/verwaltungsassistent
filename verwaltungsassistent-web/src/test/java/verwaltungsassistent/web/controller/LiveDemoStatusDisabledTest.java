package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With the live-demo indicator disabled, pages render without the banner.
 */
@SpringBootTest(properties = "app.live-demo.enabled=false")
@AutoConfigureMockMvc
class LiveDemoStatusDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionRegistry sessionRegistry;

    @MockBean
    private AuthFacade authFacade;

    private final List<String> registeredSessionIds = new ArrayList<>();

    @AfterEach
    void cleanSessions() {
        registeredSessionIds.forEach(sessionRegistry::removeSessionInformation);
        registeredSessionIds.clear();
    }

    @Test
    void disabled_pagesRenderWithoutBanner() throws Exception {
        when(authFacade.login(any(LoginCommand.class))).thenReturn(null);
        when(authFacade.currentUser(anyString())).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), "user@demo.local", "Teilnehmer", Set.of("USER")));

        MvcResult login = mockMvc.perform(formLogin("/login")
                        .user("email", "user@demo.local")
                        .password("password", "pw"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        registeredSessionIds.add(session.getId());

        mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("live-demo-banner"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("LIVE-DEMO"))));
    }
}
