package verwaltungsassistent.web.config;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthFacade authFacade;

    @Test
    void loginPage_isAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void dashboard_redirectsToLogin_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void admin_redirectsToLogin_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void cssResources_areAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/css/application.css"))
                .andExpect(status().isOk());
    }

    @Test
    void jsResources_areAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk());
    }

    @Test
    void formLogin_withValidCredentials_authenticates() throws Exception {
        var user = new AuthenticatedUser(
                UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER")
        );

        when(authFacade.login(any(LoginCommand.class)))
                .thenReturn(null); // return value not used by provider
        when(authFacade.currentUser("user@example.com"))
                .thenReturn(user);

        mockMvc.perform(formLogin("/login")
                        .user("email", "user@example.com")
                        .password("password", "password123"))
                .andExpect(authenticated())
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void formLogin_withInvalidCredentials_returnsToLogin() throws Exception {
        when(authFacade.login(any(LoginCommand.class)))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));

        mockMvc.perform(formLogin("/login")
                        .user("email", "wrong@example.com")
                        .password("password", "wrong"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void logout_redirectsToLogin() throws Exception {
        var user = new AuthenticatedUser(
                UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER")
        );

        when(authFacade.login(any(LoginCommand.class)))
                .thenReturn(null);
        when(authFacade.currentUser("user@example.com"))
                .thenReturn(user);

        mockMvc.perform(formLogin("/login")
                        .user("email", "user@example.com")
                        .password("password", "password123"))
                .andExpect(authenticated());

        mockMvc.perform(SecurityMockMvcRequestBuilders.logout())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }
}
