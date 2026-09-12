package verwaltungsassistent.web.controller;
import reasoning.auth.api.AuthenticatedUser;
import reasoning.auth.infrastructure.persistence.RefreshTokenSessionRepository;
import reasoning.auth.infrastructure.persistence.UserAccountRepository;
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
import java.util.Set;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DebugSec {
    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired RefreshTokenSessionRepository refreshTokenSessionRepository;
    @BeforeEach void setUp() {
        refreshTokenSessionRepository.deleteAll();
        userAccountRepository.deleteAll();
        var u = new AuthenticatedUser(UUID.randomUUID(), "x@test.local", "X", Set.of("ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(u, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @Test void debug() throws Exception {
        for (String p : List.of("/assistant", "/cases", "/decisions", "/admin/users", "/audit", "/dashboard")) {
            var r = mockMvc.perform(get(p)).andReturn();
            System.out.println(p + " -> " + r.getResponse().getStatus() + " Location: " + r.getResponse().getRedirectedUrl());
        }
    }
}
