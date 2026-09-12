package verwaltungsassistent.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reasoning.ai.api.AiFacade;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cookie hardening: the CSRF cookie must carry SameSite=Lax. Isolated in its
 * own class because MockMvc response-cookie assertions are order-sensitive
 * within a shared test class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CookieHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    /** Unique context for this class (response-cookie assertions are order-sensitive in shared contexts). */
    @MockBean
    private AiFacade aiFacade;

    @Test
    void csrfCookie_carriesSameSiteLax() throws Exception {
        // A CSRF-protected POST without a token is rejected (403); the
        // CsrfFilter writes the XSRF-TOKEN cookie on the response.
        var result = mockMvc.perform(post("/login"))
                .andExpect(status().isForbidden())
                .andReturn();

        var xsrf = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> "XSRF-TOKEN".equals(c.getName()))
                .findFirst();
        assertTrue(xsrf.isPresent(), "XSRF-TOKEN cookie must be set");

        if (xsrf.get() instanceof MockCookie mockCookie) {
            assertEquals("Lax", mockCookie.getSameSite(),
                    "CSRF cookie must carry SameSite=Lax");
        } else {
            // Older response-cookie representation: assert the attribute string.
            String attributes = String.valueOf(xsrf.get().getAttributes());
            assertTrue(attributes.contains("SameSite=Lax"),
                    "CSRF cookie must carry SameSite=Lax, attributes: " + attributes);
        }
    }
}
