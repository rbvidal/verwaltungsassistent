package verwaltungsassistent.web.config;

import verwaltungsassistent.web.service.LiveDemoStatusService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final RequestMatcher API_PATHS = new AntPathRequestMatcher("/api/**");

    /** Live session registry used for the admin "currently logged in" view and for expiring sessions of blocked users. */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Publishes HTTP session lifecycle events so the session registry stays accurate. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    private final AuditLogoutHandler auditLogoutHandler;
    private final boolean cookieSecure;

    public SecurityConfig(AuditLogoutHandler auditLogoutHandler,
                          @org.springframework.beans.factory.annotation.Value("${platform.security.cookie-secure:false}")
                          boolean cookieSecure) {
        this.auditLogoutHandler = auditLogoutHandler;
        this.cookieSecure = cookieSecure;
    }

    /** Records real user activity for the live-demo banner (skips its own status poll). */
    @Bean
    public LiveDemoActivityFilter liveDemoActivityFilter(LiveDemoStatusService statusService,
                                                         @org.springframework.beans.factory.annotation.Value("${app.live-demo.enabled:false}")
                                                         boolean liveDemoEnabled) {
        return new LiveDemoActivityFilter(statusService, liveDemoEnabled);
    }

    /**
     * MVC browser filter chain. Checked at @Order(0) alongside Verwaltungsassistent's apiFilterChain.
     * Matches everything EXCEPT /api/** (Verwaltungsassistent's apiFilterChain handles those with JWT).
     * Configures form-based login for browser users.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain mvcFilterChain(HttpSecurity http,
                                              LiveDemoActivityFilter liveDemoActivityFilter) throws Exception {
        return http
                .securityMatcher(new NegatedRequestMatcher(API_PATHS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/img/**", "/fonts/**", "/webjars/**").permitAll()
                        .requestMatchers("/favicon*", "/favicon.svg").permitAll()
                        .requestMatchers("/login", "/login/panel", "/register").permitAll()
                        .requestMatchers("/error/**").permitAll()
                        // Destruktive technische Wartung (Phase 2D.12): nur das
                        // versteckte SUPERADMIN-Konto — das normale ADM/Leitungs-
                        // Konto bleibt rein aufsichtlich und kann diese Endpunkte
                        // NICHT direkt aufrufen (URL-Schutz VOR der /admin/**-Regel).
                        .requestMatchers(HttpMethod.POST, "/admin/demo-reset",
                                "/admin/dataset-rebuild", "/admin/indices/purge").hasRole("SUPERADMIN")
                        // Benutzerkonten sperren/entfernen ist destruktive
                        // Kontenverwaltung: nur die technische Rolle SUPERADMIN.
                        // Die Leitung (ADMIN) sieht die Konten, darf aber keine
                        // Konten sperren oder entfernen (Aufsichts-Prinzip).
                        .requestMatchers(HttpMethod.POST, "/admin/users/*/block",
                                "/admin/users/*/remove").hasRole("SUPERADMIN")
                        .requestMatchers("/admin/data-restore/**", "/cases/demo-data")
                                .hasRole("SUPERADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/audit/**").hasAnyRole("AUDITOR", "ADMIN")
                        .requestMatchers("/corpus/**").hasAnyRole("AUDITOR", "ADMIN")
                        .requestMatchers("/decisions/**").hasAnyRole("USER", "ANALYST", "AUDITOR", "ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/dashboard", true)
                        // A blocked account is only reported after the password
                        // check succeeded (AuthService), so this never leaks
                        // account existence for unknown or wrong credentials.
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof reasoning.auth.application.AccountLockedException) {
                                response.sendRedirect("/login?error=blocked");
                            } else if (exception instanceof org.springframework.security.web.authentication.session.SessionAuthenticationException) {
                                // Konto bereits in einem anderen Browser aktiv:
                                // eigener Hinweis statt generischem Fehler.
                                response.sendRedirect("/login?error=occupied");
                            } else {
                                response.sendRedirect("/login?error");
                            }
                        })
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .addLogoutHandler(auditLogoutHandler)
                        // Explizite Freigabe beim Logout: Der SessionRegistry-
                        // Eintrag („in Benutzung" auf der Login-Seite) wird sofort
                        // entfernt und nicht erst auf das container-seitige
                        // sessionDestroyed-Ereignis gewartet — andernfalls bliebe
                        // das Konto nach dem Abmelden fälschlich „in Benutzung".
                        .addLogoutHandler((request, response, authentication) -> {
                            jakarta.servlet.http.HttpSession session = request.getSession(false);
                            if (session != null) {
                                sessionRegistry().removeSessionInformation(session.getId());
                            }
                        })
                        .permitAll()
                )
                .sessionManagement(session -> session
                        // Demo-Konten sind EIN-Personen-Konten: maximal EINE
                        // aktive Sitzung pro Zugang. maxSessionsPreventsLogin
                        // blockiert den Login aus einem zweiten Browser, solange
                        // die erste Sitzung lebt (kein stilles Verdrängen).
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(true)
                        .sessionRegistry(sessionRegistry())
                        .expiredUrl("/login?expired")
                )
                .addFilterAfter(liveDemoActivityFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAt(new ConcurrentSessionFilter(sessionRegistry(), (SessionInformationExpiredEvent event) -> {
                    HttpServletResponse response = event.getResponse();
                    if ("true".equals(event.getRequest().getHeader("HX-Request"))) {
                        // htmx requests get a 401 so app.js redirects to the login page cleanly
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }
                    try {
                        response.sendRedirect("/login?expired");
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException("Could not redirect expired session", ex);
                    }
                }), ConcurrentSessionFilter.class)
                .csrf(csrf -> {
                    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                    // SameSite=Lax hardens cross-site requests without breaking
                    // the same-origin htmx flow; Secure follows the deployment
                    // (false for local HTTP demo, true behind the HTTPS proxy).
                    repository.setCookieCustomizer(cookie -> cookie
                            .sameSite("Lax")
                            .secure(cookieSecure));
                    csrf.csrfTokenRepository(repository);
                })
                .headers(headers -> headers
                        // SAMEORIGIN instead of DENY: the document viewer modal
                        // embeds PDFs via <embed src="/documents/{id}/content">
                        // and Chrome's PDF viewer refuses to render a PDF whose
                        // response carries X-Frame-Options: DENY. Cross-origin
                        // framing remains blocked.
                        .frameOptions(frame -> frame.sameOrigin())
                        .xssProtection(xss -> xss.disable())
                        // Defense-in-depth for content delivery: documents are
                        // additionally served with nosniff in the controller.
                        // CSP is pragmatic (inline scripts/styles are part of the
                        // current Thymeleaf/htmx/Alpine templates) but restricts
                        // plugin/object/script sources and framing.
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Content-Security-Policy",
                                "default-src 'self'; "
                                        // Alpine.js evaluates x-data/x-show/@-Handler
                                        // expressions via new Function — ohne
                                        // 'unsafe-eval' bleiben ALLE Alpine-
                                        // Komponenten (Modal/Palette, x-cloak,
                                        // Esc/Backdrop-Handler) funktionslos und
                                        // die Palette bleibt sichtbar hängen.
                                        + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data: blob: https:; "
                                        + "font-src 'self' data:; "
                                        + "connect-src 'self'; "
                                        + "media-src 'self' blob:; "
                                        + "object-src 'self'; "
                                        + "frame-src 'self'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "frame-ancestors 'self'"))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Referrer-Policy", "strict-origin-when-cross-origin"))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "Permissions-Policy",
                                "geolocation=(), camera=(), microphone=(), usb=(), interest-cohort=()"))
                        .addHeaderWriter(new StaticHeadersWriter(
                                "X-Content-Type-Options", "nosniff"))
                )
                .build();
    }
}
