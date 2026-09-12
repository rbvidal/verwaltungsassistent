package verwaltungsassistent.web.controller;

import reasoning.audit.api.AuditEvent;
import reasoning.audit.api.AuditEventPage;
import reasoning.audit.api.AuditQuery;
import reasoning.audit.api.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only audit view over the existing audit infrastructure.
 * Server-side authorization: /audit/** requires AUDITOR or ADMIN
 * (enforced by SecurityConfig). This controller only queries and renders
 * real events — no events are fabricated and nothing is written.
 */
@Controller
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final int PAGE_SIZE = 25;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** How far back the audit view shows by default; older records stay in the database. */
    private static final int DEFAULT_DAYS = 3;

    /** How many page-navigation steps the unfiltered audit view exposes. */
    private static final int DEFAULT_VISIBLE_PAGES = 3;

    @GetMapping("/audit")
    public String audit(@RequestParam(defaultValue = "0") int page,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(required = false) String actor,
                        Model model) {
        page = Math.max(0, page);
        ZoneId zone = ZoneId.systemDefault();

        // Only values the user actually entered count as a filter; the default
        // 3-day window alone must not enable full pagination.
        String userFrom = from != null && !from.isBlank() ? from.trim() : null;
        String userTo = to != null && !to.isBlank() ? to.trim() : null;
        String userActor = actor != null && !actor.isBlank() ? actor.trim() : null;
        boolean filterActive = userFrom != null || userTo != null || userActor != null;

        String effectiveFrom = userFrom != null ? userFrom
                : LocalDate.now().minus(DEFAULT_DAYS, ChronoUnit.DAYS).toString();
        String effectiveTo = userTo != null ? userTo : "";
        Instant fromInstant = parseDateStart(effectiveFrom, zone);
        if (fromInstant == null) {
            fromInstant = Instant.now().minus(DEFAULT_DAYS, ChronoUnit.DAYS);
        }
        Instant toInstant = null;
        if (!effectiveTo.isEmpty()) {
            Instant toStart = parseDateStart(effectiveTo, zone);
            if (toStart != null) toInstant = toStart.plus(1, ChronoUnit.DAYS);
        }

        // Without a user filter only the latest pages are reachable; a direct
        // page parameter beyond that range is clamped into the exposed window.
        if (!filterActive) {
            page = Math.min(page, DEFAULT_VISIBLE_PAGES - 1);
        }

        AuditEventPage eventPage;
        try {
            eventPage = auditService.query(new AuditQuery(
                    null, userActor, null, null, null, null, null, null,
                    fromInstant, toInstant, page, PAGE_SIZE, List.of()));
        } catch (Exception e) {
            log.warn("Audit query failed: {}", e.getMessage());
            eventPage = new AuditEventPage(List.of(), 0, PAGE_SIZE, 0, 0);
        }

        int totalPages = eventPage.totalPages() > 0 ? eventPage.totalPages() : 1;
        int displayPages = filterActive ? totalPages : Math.min(totalPages, DEFAULT_VISIBLE_PAGES);

        model.addAttribute("rows", toRows(eventPage.events()));
        model.addAttribute("page", page);
        model.addAttribute("totalPages", displayPages);
        model.addAttribute("totalElements", eventPage.totalElements());
        model.addAttribute("from", userFrom != null ? userFrom : "");
        model.addAttribute("to", userTo != null ? userTo : "");
        model.addAttribute("actor", userActor != null ? userActor : "");
        model.addAttribute("pageTitle", "Audit");
        model.addAttribute("activeSection", "audit");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Audit", "/audit")));
        return "audit/list";
    }

    private static Instant parseDateStart(String date, ZoneId zone) {
        try {
            return LocalDate.parse(date).atStartOfDay(zone).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Exports exactly the selected audit rows as UTF-8 CSV.
     * Server-side (admin-only) — the same authorization as the audit page.
     */
    @PostMapping("/audit/export")
    public void export(@RequestParam(value = "ids", required = false) List<String> ids,
                       HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        if (ids == null || ids.isEmpty()) {
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Bitte wählen Sie mindestens einen Eintrag aus.");
            return;
        }
        List<String> validIds = ids.stream()
                .filter(s -> s != null && s.matches("[0-9a-fA-F-]{36}"))
                .toList();
        if (validIds.isEmpty()) {
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Bitte wählen Sie mindestens einen Eintrag aus.");
            return;
        }

        AuditEventPage page;
        try {
            page = auditService.query(new AuditQuery(
                    null, null, null, null, null, null, null, null, null, null,
                    0, 200, validIds));
        } catch (Exception e) {
            log.warn("Audit export query failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("Export fehlgeschlagen.");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"verwaltungsassistent-audit-" + java.time.LocalDate.now() + ".csv\"");
        response.getWriter().write('﻿'); // BOM for spreadsheet compatibility
        response.getWriter().write(
                "Zeitpunkt;Benutzer;Ereignis;Objekt;Kennung;Anfrage;IP;Quelle;Details\r\n");
        for (AuditRow r : toRows(page.events())) {
            response.getWriter().write(toCsvLine(r) + "\r\n");
        }
    }

    /**
     * Attribution is derived from what the audit infrastructure actually
     * recorded: an event with a client IP came from an HTTP request
     * (Web-Anfrage); one without is a genuine background operation
     * (Hintergrundprozess). No IP is ever invented.
     */
    private static String sourceOf(AuditEvent e) {
        return e.ipAddress() != null && !e.ipAddress().isBlank() ? "Web-Anfrage" : "Hintergrundprozess";
    }

    private List<AuditRow> toRows(List<AuditEvent> events) {
        return events.stream()
                .map(e -> new AuditRow(
                        e.id() != null ? e.id().toString() : "",
                        e.timestamp() != null
                                ? DATE_FMT.format(e.timestamp().atZone(ZoneId.systemDefault())) : "—",
                        e.actorId() != null ? e.actorId() : "—",
                        label(e.eventType() != null ? e.eventType().name() : "—"),
                        e.entityType() != null ? e.entityType() : "—",
                        e.entityId() != null ? e.entityId() : "—",
                        e.requestPath() != null ? e.requestPath() + (e.httpMethod() != null ? " [" + e.httpMethod() + "]" : "") : "—",
                        e.ipAddress() != null ? e.ipAddress() : "—",
                        sourceOf(e),
                        e.metadata() != null && !e.metadata().isNull()
                                ? abbreviate(e.metadata().toString(), 120) : "—",
                        e.metadata() != null && !e.metadata().isNull()
                                ? e.metadata().toString() : ""))
                .toList();
    }

    private static String toCsvLine(AuditRow r) {
        return String.join(";",
                csv(r.timestamp()), csv(r.actor()), csv(r.eventLabel()),
                csv(r.entityType()), csv(r.entityId()), csv(r.request()),
                csv(r.ip()), csv(r.source()), csv(r.metadata()));
    }

    private static String csv(String v) {
        String s = v == null ? "" : v;
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** German label for technical event-type names (same convention as the dashboard activity feed). */
    private static String label(String eventTypeName) {
        return switch (eventTypeName) {
            case "USER_LOGIN" -> "Anmeldung";
            case "USER_LOGIN_FAILED" -> "Anmeldung fehlgeschlagen";
            case "USER_LOGOUT" -> "Abmeldung";
            case "USER_CREATED" -> "Benutzer angelegt";
            case "ROLE_CHANGED" -> "Rolle geändert";
            case "DOCUMENT_INGESTED" -> "Dokument verarbeitet";
            case "DOCUMENT_UPDATED" -> "Dokument aktualisiert";
            case "DOCUMENT_VIEWED" -> "Dokument geöffnet";
            case "DOCUMENT_DELETED" -> "Dokument gelöscht";
            case "SEARCH_EXECUTED" -> "Suche";
            case "RETRIEVAL_EXECUTED" -> "Dokumentabruf";
            case "MODEL_INFERENCE" -> "KI-Analyse";
            case "PROMPT_EXECUTED" -> "Prompt ausgeführt";
            default -> eventTypeName.replace("_", " ");
        };
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public record AuditRow(String id, String timestamp, String actor, String eventLabel,
                           String entityType, String entityId, String request, String ip,
                           String source, String metadata, String details) {}
}
