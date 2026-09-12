package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import verwaltungsassistent.web.service.DemoDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: Logout muss das Demo-Konto im SessionRegistry sofort freigeben
 * („in Benutzung" → „frei" auf der Login-Seite) — gemessen am tatsächlichen
 * Mechanismus (SessionRegistry), nicht an HTML-Text.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoAccountLogoutReleaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private DemoDataService demoDataService;

    @Autowired
    private reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository;

    @BeforeEach
    void seedDemoUsers() {
        // Nur einmalig erzeugen (geteilter Test-Kontext/Datenbank): generate()
        // ist nicht idempotent, wenn die Demo-Nutzer bereits existieren.
        if (userAccountRepository.findByEmail("demo02@verwaltungsassistent.local").isEmpty()) {
            demoDataService.generate();
        }
    }

    private void login(MockHttpSession session, String email) throws Exception {
        mvc.perform(post("/login").session(session)
                        .param("email", email)
                        .param("password", DemoDataService.DEMO_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private void logout(MockHttpSession session) throws Exception {
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private boolean inUse(String email) {
        return sessionRegistry.getAllPrincipals().stream()
                .filter(p -> p instanceof AuthenticatedUser)
                .map(p -> (AuthenticatedUser) p)
                .anyMatch(u -> email.equalsIgnoreCase(u.email()));
    }

    @org.junit.jupiter.api.AfterEach
    void releaseAllDemoSessions() {
        // Geteilter Test-Kontext: jede Sitzung der Demo-Konten entfernen,
        // damit die Tests unabhängig von der Ausführungsreihenfolge sind.
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (principal instanceof AuthenticatedUser u
                    && (u.email().startsWith("demo") || u.email().startsWith("admin")
                        || u.email().startsWith("superadmin"))) {
                for (var info : sessionRegistry.getAllSessions(principal, false)) {
                    sessionRegistry.removeSessionInformation(info.getSessionId());
                }
            }
        }
    }

    @Test
    void logoutReleasesAccountImmediately() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, "demo02@verwaltungsassistent.local");
        assertTrue(inUse("demo02@verwaltungsassistent.local"), "nach Login muss demo02 in Benutzung sein");

        logout(session);

        assertFalse(inUse("demo02@verwaltungsassistent.local"), "nach Logout muss demo02 frei sein");
    }

    @Test
    void loginLogoutLoginAgainWorks() throws Exception {
        for (int round = 1; round <= 2; round++) {
            MockHttpSession session = new MockHttpSession();
            login(session, "demo02@verwaltungsassistent.local");
            assertTrue(inUse("demo02@verwaltungsassistent.local"), "Runde " + round + ": in Benutzung nach Login");
            logout(session);
            assertFalse(inUse("demo02@verwaltungsassistent.local"), "Runde " + round + ": frei nach Logout");
        }
    }

    @Test
    void secondBrowserLoginIsBlockedWhileAccountIsActive() throws Exception {
        MockHttpSession first = new MockHttpSession();
        login(first, "demo02@verwaltungsassistent.local");
        assertTrue(inUse("demo02@verwaltungsassistent.local"));

        // Zweiter Browser / zweite Sitzung für dasselbe Konto: Login wird
        // verweigert (maximumSessions(1) + maxSessionsPreventsLogin).
        MockHttpSession second = new MockHttpSession();
        mvc.perform(post("/login").session(second)
                        .param("email", "demo02@verwaltungsassistent.local")
                        .param("password", DemoDataService.DEMO_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrlPattern("/login?error*"));

        // Die erste Sitzung bleibt gültig, die zweite ist nicht angemeldet.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/dashboard").session(second))
                .andExpect(status().is3xxRedirection());
        assertTrue(inUse("demo02@verwaltungsassistent.local"), "erste Sitzung bleibt aktiv");
    }

    @Test
    void demo02LogoutAllowsDemo03Login() throws Exception {
        MockHttpSession s2 = new MockHttpSession();
        login(s2, "demo02@verwaltungsassistent.local");
        logout(s2);
        assertFalse(inUse("demo02@verwaltungsassistent.local"));

        MockHttpSession s3 = new MockHttpSession();
        login(s3, "demo03@verwaltungsassistent.local");
        assertTrue(inUse("demo03@verwaltungsassistent.local"), "demo03 muss nach Login in Benutzung sein");
        assertFalse(inUse("demo02@verwaltungsassistent.local"), "demo02 bleibt nach Logout frei");
    }
}
