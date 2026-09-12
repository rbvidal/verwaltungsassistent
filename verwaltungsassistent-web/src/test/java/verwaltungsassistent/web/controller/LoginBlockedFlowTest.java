package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.application.AccountLockedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The blocked-account login behavior: only VALID credentials on a blocked
 * account produce the specific blocked message; wrong credentials always get
 * the generic error (no account-existence leak).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginBlockedFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthFacade authFacade;

    @Test
    void blockedAccount_withValidCredentials_redirectsToBlockedMessage() throws Exception {
        when(authFacade.login(any())).thenThrow(new AccountLockedException());

        mockMvc.perform(post("/login")
                        .param("email", "blocked@example.com")
                        .param("password", "correct-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=blocked"));
    }

    @Test
    void wrongPassword_redirectsToGenericError() throws Exception {
        when(authFacade.login(any())).thenThrow(new BadCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/login")
                        .param("email", "blocked@example.com")
                        .param("password", "wrong-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void unknownEmail_redirectsToGenericError() throws Exception {
        when(authFacade.login(any())).thenThrow(new BadCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/login")
                        .param("email", "unknown@example.com")
                        .param("password", "whatever")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void activeAccount_withValidCredentials_logsIn() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), "active@example.com", "Aktiver Nutzer", Set.of("USER"));
        when(authFacade.currentUser("active@example.com")).thenReturn(user);

        mockMvc.perform(post("/login")
                        .param("email", "active@example.com")
                        .param("password", "correct-password")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));
    }
}
