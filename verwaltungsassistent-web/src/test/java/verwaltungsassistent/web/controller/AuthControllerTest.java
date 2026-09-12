package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPage_returnsLoginForm() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attributeExists("loginForm"));
    }

    @Test
    void loginPage_withError_showsErrorMessage() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("loginError",
                        "Ungültige E-Mail oder Passwort"));
    }

    @Test
    void loginPage_withBlockedError_showsBlockedMessage() throws Exception {
        mockMvc.perform(get("/login").param("error", "blocked"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("loginError",
                        reasoning.auth.application.AccountLockedException.MESSAGE));
    }

    @Test
    void loginPage_withLogout_showsLogoutMessage() throws Exception {
        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("logoutMessage",
                        "Sie wurden erfolgreich abgemeldet"));
    }

    @Test
    void loginPage_withExpired_showsExpiredMessage() throws Exception {
        mockMvc.perform(get("/login").param("expired", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("expiredMessage",
                        "Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an"));
    }

    @Test
    void loginPage_withRegistered_showsRegisteredMessage() throws Exception {
        mockMvc.perform(get("/login").param("registered", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("registeredMessage",
                        "Registrierung erfolgreich. Bitte melden Sie sich an"));
    }

    @Test
    void loginPage_formHasEmailAndPasswordFields() throws Exception {
        String content = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(content).contains("name=\"email\"");
        assertThat(content).contains("name=\"password\"");
        assertThat(content).contains("type=\"email\"");
        assertThat(content).contains("type=\"password\"");
    }
}
