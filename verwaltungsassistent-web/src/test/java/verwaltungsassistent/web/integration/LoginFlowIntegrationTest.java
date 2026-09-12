package verwaltungsassistent.web.integration;

import reasoning.auth.api.AuthFacade;
import reasoning.auth.api.AuthTokens;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.api.LoginCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginFlowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private AuthFacade authFacade;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    @Test
    void loginPage_returnsLoginForm() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/login", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Anmelden");
        assertThat(response.getBody()).contains("E-Mail-Adresse");
        assertThat(response.getBody()).contains("Passwort");
    }

    @Test
    void loginPage_withError_showsError() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/login?error", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Ungültige E-Mail oder Passwort");
    }

    @Test
    void loginPage_withLogout_showsMessage() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/login?logout", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("erfolgreich abgemeldet");
    }

    @Test
    void unauthenticatedDashboard_redirectsToLogin() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/dashboard", String.class);

        // Redirect to login — the response body will be the login form
        assertThat(response.getBody()).contains("Anmelden");
    }

    @Test
    void cssResources_loadSuccessfully() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/css/application.css", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .contains("text/css");
    }

    @Test
    void loginForm_hasCsrfToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/login", String.class);

        // CSRF cookie should be set
        assertThat(response.getHeaders().get("Set-Cookie")).isNotNull();
        String cookies = String.join(";", response.getHeaders().get("Set-Cookie"));
        assertThat(cookies).contains("XSRF-TOKEN");
    }
}
