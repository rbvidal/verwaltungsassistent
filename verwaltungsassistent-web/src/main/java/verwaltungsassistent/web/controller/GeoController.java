package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentCategory;
import reasoning.common.model.DocumentFileType;
import reasoning.document.api.DocumentFacade;
import reasoning.document.api.CreateDocumentCommand;
import reasoning.workspace.api.AttachDocumentCommand;
import reasoning.workspace.api.CreateWorkspaceCommand;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import verwaltungsassistent.web.geo.GeoPhotoService.ExtractedGps;
import verwaltungsassistent.web.security.CaseAccessGuard;
import verwaltungsassistent.web.service.GeoService;
import verwaltungsassistent.web.service.GeoService.GeoAddress;
import verwaltungsassistent.web.service.GeoService.Jurisdiction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Geoinformation module: Stadtkarte (Leaflet over local GeoJSON), Geotagged
 * Fotos (EXIF extraction) and Geovorgänge (geo cases). The jurisdiction is
 * determined deterministically from structured geographic data — the map is
 * only the visualization.
 */
@Controller
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);

    // Demo-Betrieb: Leitungs-Konto vollständig schreibgeschützt (Bean fehlt
    // außerhalb des demo-Profils → kein Effekt).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private verwaltungsassistent.web.demo.DemoReadOnlyPolicy demoReadOnlyPolicy;

    private void denyDemoAdmin(reasoning.auth.api.AuthenticatedUser user) {
        if (demoReadOnlyPolicy != null) {
            demoReadOnlyPolicy.denyAdminMutation(user);
        }
    }

    private final GeoService geoService;
    private final GeoPhotoRepository photoRepository;
    private final GeoPhotoService photoService;
    private final WorkspaceService workspaceService;
    private final DocumentFacade documentFacade;
    private final CaseAccessGuard caseAccessGuard;
    private final reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository;
    private final verwaltungsassistent.web.service.CaseTimelineService caseTimelineService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Verfügbare Kategorien für Geovorgänge (gleiche Kategorien wie die Demo-Fotos). */
    private static final List<String> GEO_CATEGORIES = List.of(
            "Müllablagerung", "Straßenschaden", "Baustelle / Bauaufsicht",
            "Parken / Ordnungswidrigkeit", "Öffentliche Anlage", "Verkehrsschild",
            "Straßenbeleuchtung", "Sonstiges");

    public GeoController(GeoService geoService,
                         GeoPhotoRepository photoRepository,
                         GeoPhotoService photoService,
                         WorkspaceService workspaceService,
                         DocumentFacade documentFacade,
                         CaseAccessGuard caseAccessGuard,
                         reasoning.auth.infrastructure.persistence.UserAccountRepository userAccountRepository,
                         verwaltungsassistent.web.service.CaseTimelineService caseTimelineService) {
        this.geoService = geoService;
        this.photoRepository = photoRepository;
        this.photoService = photoService;
        this.workspaceService = workspaceService;
        this.documentFacade = documentFacade;
        this.caseAccessGuard = caseAccessGuard;
        this.userAccountRepository = userAccountRepository;
        this.caseTimelineService = caseTimelineService;
    }

    // ── Hauptseite + Panels ────────────────────────────────────────────────

    @GetMapping("/geoinformation")
    public String overview(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        return map(user, model);
    }

    @GetMapping("/geoinformation/map")
    public String map(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        addChrome(model, "Stadtkarte");
        model.addAttribute("geoActive", "map");
        model.addAttribute("demoAddresses", geoService.addresses());
        return "geoinformation/index";
    }

    @GetMapping("/geoinformation/photos")
    public String photos(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        addChrome(model, "Geotagged Fotos");
        model.addAttribute("geoActive", "photos");
        // Only genuine photographs: supported content type AND real image
        // signature — an attached PDF can never appear as a photograph.
        // Geotagged Fotos sind Teil des gemeinsamen Außendienst-Registers
        // (wie die Geovorgänge): jede Mitarbeiterin sieht die Fotos des
        // Registers — nicht nur die eigenen. uploadedBy bleibt je Karte
        // sichtbar; der Demo-Datensatz hält so für jedes Mitarbeiter-Konto
        // sinnvollen GEO-Inhalt bereit, ohne Duplikate zu erzeugen.
        List<GeoPhotoEntity> entities = photoRepository.findAll().stream()
                .filter(p -> p.getContentType() != null
                        && (p.getContentType().equalsIgnoreCase("image/jpeg")
                            || p.getContentType().equalsIgnoreCase("image/png")))
                .filter(photoService::isSupportedPhoto)
                .sorted(java.util.Comparator
                        .comparing(GeoPhotoEntity::getCreatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
        // Deterministic jurisdiction per photo — the extracted coordinates are
        // resolved against the bundled Verwaltungsgebiete (never by guessing).
        List<PhotoCard> cards = new ArrayList<>();
        for (GeoPhotoEntity p : entities) {
            Jurisdiction j = p.isHasGeo() && p.getLatitude() != null
                    ? geoService.jurisdictionFor(p.getLatitude(), p.getLongitude())
                    : new Jurisdiction(null, null, false);
            String nearest = null;
            Integer nearestMeters = null;
            if (p.isHasGeo() && p.getLatitude() != null) {
                var near = geoService.nearestAddress(p.getLatitude(), p.getLongitude(), 500);
                if (near.isPresent()) {
                    nearest = near.get().fullAddress();
                    nearestMeters = (int) Math.round(GeoService.distanceMeters(
                            p.getLatitude(), p.getLongitude(), near.get().lat(), near.get().lon()));
                }
            }
            // Der verknüpfte Geovorgang trägt die eigentliche Bedeutung des
            // Fotos (Fallname, Beschreibung, Kategorie) — direkt anzeigen.
            String[] caseInfo = new String[3]; // Name, Beschreibung, Kategorie
            if (p.getWorkspaceId() != null) {
                try {
                    workspaceService.findById(p.getWorkspaceId().toString()).ifPresent(ws -> {
                        caseInfo[0] = ws.getName();
                        caseInfo[1] = ws.getDescription();
                        Object cat = ws.getPhaseDataMap().get("geoCategory");
                        if (cat != null) caseInfo[2] = String.valueOf(cat);
                    });
                } catch (Exception e) {
                    log.debug("Fallangaben zum Foto nicht ladbar: {}", e.getMessage());
                }
            }
            String caseName = caseInfo[0];
            String caseDescription = caseInfo[1];
            String caseCategory = caseInfo[2];
            cards.add(new PhotoCard(p.getId(), p.getOriginalName(), p.getContentType(),
                    p.isHasGeo(), p.getLatitude(), p.getLongitude(), p.getCapturedAt(),
                    p.getOrientation(), p.getGpsSource(), p.getWorkspaceId(), p.getUploadedBy(),
                    p.getCreatedAt(),
                    j.resolved() ? j.district() : null, j.resolved() ? j.authority() : null,
                    j.resolved(), nearest, nearestMeters,
                    caseName, caseDescription, caseCategory));
        }
        model.addAttribute("photoCards", cards);
        return "geoinformation/index";
    }

    /** View model for one photo card incl. deterministically resolved jurisdiction. */
    public record PhotoCard(UUID id, String originalName, String contentType, boolean hasGeo,
                            Double latitude, Double longitude, Instant capturedAt, String orientation,
                            String gpsSource, UUID workspaceId, String uploadedBy, Instant createdAt,
                            String district, String authority, boolean jurisdictionResolved,
                            String nearestAddress, Integer nearestMeters,
                            String caseName, String caseDescription, String caseCategory) {}

    @GetMapping("/geoinformation/geo-cases")
    public String geoCases(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        addChrome(model, "Geovorgänge");
        model.addAttribute("geoActive", "geo-cases");
        model.addAttribute("geoCases", loadGeoCases(user));
        return "geoinformation/index";
    }

    private List<GeoCaseRow> loadGeoCases(AuthenticatedUser user) {
        List<GeoCaseRow> rows = new ArrayList<>();
        try {
            // Geovorgänge sind das gemeinsame Außendienst-Register der
            // Verwaltung: Jede Mitarbeiterin sieht alle Geovorgänge (nicht nur
            // die eigenen) — dieselbe Sicht wie die Straßenkarte/Foto-Galerie.
            // Für Vorgänge (CASE) gilt weiterhin die eigene+Pool-Sichtbarkeit.
            List<WorkspaceEntity> all;
            if (user != null && user.roles() != null && user.roles().contains("ADMIN")) {
                all = new ArrayList<>(workspaceService.findAll());
            } else if (user != null) {
                java.util.Map<String, WorkspaceEntity> byId = new java.util.LinkedHashMap<>();
                for (WorkspaceEntity ws : verwaltungsassistent.web.security.WorkspaceVisibility
                        .ownAndPool(workspaceService, user.email())) {
                    byId.put(ws.getId(), ws);
                }
                for (WorkspaceEntity ws : workspaceService.findAll()) {
                    if ("GEO".equalsIgnoreCase(ws.getWorkspaceType())) {
                        byId.putIfAbsent(ws.getId(), ws);
                    }
                }
                all = new ArrayList<>(byId.values());
            } else {
                all = List.of();
            }
            for (WorkspaceEntity ws : all) {
                if (ws.getGeoLatitude() == null && ws.getGeoLongitude() == null
                        && !"GEO".equalsIgnoreCase(ws.getWorkspaceType())) {
                    continue;
                }
                rows.add(new GeoCaseRow(
                        ws.getId().toString(),
                        ws.getWorkspaceCode(),
                        ws.getName(),
                        ws.getGeoAddress() != null ? ws.getGeoAddress()
                                : (ws.getGeoStreet() != null
                                        ? ws.getGeoStreet() + (ws.getGeoHouseNumber() != null ? " " + ws.getGeoHouseNumber() : "")
                                        : "—"),
                        ws.getGeoDistrict() != null ? ws.getGeoDistrict() : "—",
                        ws.getGeoAuthority() != null ? ws.getGeoAuthority() : "—",
                        ws.getCreatedAt() != null ? DateTimeFormatter.ofPattern("dd.MM.yyyy")
                                .format(ws.getCreatedAt().atZone(java.time.ZoneId.systemDefault())) : "—",
                        ws.getOwnerId() != null ? ws.getOwnerId() : "—",
                        CaseController.statusLabel(ws.getStatus())));
            }
            rows.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        } catch (Exception e) {
            log.warn("Could not load geo cases: {}", e.getMessage());
        }
        return rows;
    }

    public record GeoCaseRow(String id, String code, String name, String location,
                             String district, String authority, String createdAt,
                             String owner, String status) {}

    // ── API (deterministisch, lokal) ───────────────────────────────────────

    /** Brandenburger Verwaltungsgebiete as GeoJSON — served locally for the map. */
    @GetMapping(value = "/geoinformation/api/bezirke", produces = "application/geo+json")
    @ResponseBody
    public ResponseEntity<byte[]> bezirkeGeoJson() {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/geo+json"))
                    .body(new ClassPathResource("geodata/brandenburg-verwaltungsgebiete.geojson").getInputStream().readAllBytes());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Reale Straßen-Geometrie (OpenStreetMap/ODbL, lokal gebündelt) für die
     * Straßenkarte: Straßenlinien der Demo-Stadt Werder (Havel) — kein
     * erfundener Verlauf, keine externen Kacheln.
     */
    @GetMapping(value = "/geoinformation/api/streets", produces = "application/geo+json")
    @ResponseBody
    public ResponseEntity<byte[]> streetsGeoJson() {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/geo+json"))
                    .body(new ClassPathResource("geodata/brandenburg-streets-werder.geojson").getInputStream().readAllBytes());
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Local deterministic address search over the bundled demo dataset. */
    @GetMapping("/geoinformation/api/addresses")
    @ResponseBody
    public Map<String, Object> addressSearch(@RequestParam("q") String q) {
        var result = geoService.searchAddressDetailed(q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (GeoAddress a : result.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("street", a.street());
            item.put("houseNumber", a.houseNumber());
            item.put("postalCode", a.postalCode());
            item.put("city", a.city());
            item.put("district", a.district());
            item.put("lat", a.lat());
            item.put("lon", a.lon());
            item.put("address", a.fullAddress());
            item.put("jurisdiction", jurisdictionView(a.lat(), a.lon()));
            out.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", out);
        response.put("count", out.size());
        response.put("query", q);
        // ehrliche Genauigkeit: exakte Hausnummer gefunden oder nur ähnliche Treffer
        response.put("exactMatch", result.exactHouseNumber());
        return response;
    }

    /** Deterministische Zuständigkeitsbestimmung für eine Koordinate. */
    @GetMapping("/geoinformation/api/jurisdiction")
    @ResponseBody
    public Map<String, Object> jurisdiction(@RequestParam("lat") double lat, @RequestParam("lon") double lon) {
        return jurisdictionView(lat, lon);
    }

    /** Bekannte Adressen in der Umgebung einer Koordinate (lokaler Datensatz). */
    @GetMapping("/geoinformation/api/nearby")
    @ResponseBody
    public Map<String, Object> nearby(@RequestParam("lat") double lat,
                                      @RequestParam("lon") double lon,
                                      @RequestParam(value = "radius", defaultValue = "1500") double radius) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GeoAddress a : geoService.addresses()) {
            double d = GeoService.distanceMeters(lat, lon, a.lat(), a.lon());
            if (d <= radius) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("address", a.fullAddress());
                item.put("street", a.street());
                item.put("houseNumber", a.houseNumber());
                item.put("postalCode", a.postalCode());
                item.put("city", a.city());
                item.put("district", a.district());
                item.put("lat", a.lat());
                item.put("lon", a.lon());
                item.put("distanceMeters", Math.round(d));
                out.add(item);
            }
        }
        out.sort(Comparator.comparingLong(m -> (Long) m.get("distanceMeters")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", out);
        result.put("count", out.size());
        return result;
    }

    /** Reverse-Geocoding: nächste bekannte Adresse im lokalen Adressbestand. */
    @GetMapping("/geoinformation/api/reverse")
    @ResponseBody
    public Map<String, Object> reverse(@RequestParam("lat") double lat,
                                       @RequestParam("lon") double lon,
                                       @RequestParam(value = "radius", defaultValue = "1500") double radius) {
        GeoService.GeoAddress nearest = geoService.nearestAddress(lat, lon, radius).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (nearest != null) {
            double d = GeoService.distanceMeters(lat, lon, nearest.lat(), nearest.lon());
            result.put("found", true);
            result.put("address", nearest.fullAddress());
            result.put("street", nearest.street());
            result.put("houseNumber", nearest.houseNumber());
            result.put("postalCode", nearest.postalCode());
            result.put("city", nearest.city());
            result.put("district", nearest.district());
            result.put("lat", nearest.lat());
            result.put("lon", nearest.lon());
            result.put("distanceMeters", Math.round(d));
            return result;
        }
        // Keine Adresse im erfassten Bestand: Fallback auf die nächste erfasste
        // OSM-Straße (nur Name, keine Hausnummer) — echte Geodaten, nichts
        // Erfundenes. Ist auch das nicht möglich, wird die Abdeckungsgrenze
        // ehrlich benannt statt eine erfundene Adresse zu liefern.
        GeoService.StreetWay street = geoService.nearestStreet(lat, lon, 500).orElse(null);
        if (street != null) {
            double sd = GeoService.distanceMeters(lat, lon, street.points().getFirst()[1], street.points().getFirst()[0]);
            result.put("found", false);
            result.put("streetOnly", true);
            result.put("street", street.name());
            result.put("address", street.name());
            result.put("distanceMeters", Math.round(sd));
            result.put("message", "Keine Hausnummer im lokalen Adressbestand — nächste erfasste Straße: "
                    + street.name() + ".");
            return result;
        }
        result.put("found", false);
        result.put("message", "Keine Adresse im lokalen Adressbestand. Der lokale Bestand umfasst "
                + "Adressen der Demo-Orte Potsdam, Oranienburg, Brandenburg an der Havel, Werder (Havel), "
                + "Teltow, Falkensee, Bernau bei Berlin und Königs Wusterhausen sowie das Straßennetz von "
                + "Werder (Havel) — dieser Punkt liegt außerhalb der erfassten Demo-Daten.");
        return result;
    }

    private Map<String, Object> jurisdictionView(double lat, double lon) {
        Jurisdiction j = geoService.jurisdictionFor(lat, lon);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resolved", j.resolved());
        out.put("district", j.district());
        out.put("authority", j.authority());
        out.put("deterministic", true);
        out.put("method", "Punkt-in-Polygon über die lokalen Verwaltungsgebiets-Grenzen (GeoJSON-Daten)");
        return out;
    }

    // ── Geotagged Fotos ────────────────────────────────────────────────────

    @PostMapping("/geoinformation/photos/upload")
    public String uploadPhoto(@RequestParam("file") MultipartFile file,
                              @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte ein Foto auswählen.");
        }
        // Browser-Anzeige von TIFF ist nicht möglich → nur die unterstützten
        // Formate akzeptieren (ehrliche Fehlermeldung statt kaputter Vorschau).
        String contentType = file.getContentType() != null ? file.getContentType() : "";
        if (!GeoPhotoService.isSupportedContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nur JPG/JPEG und PNG werden unterstützt (Smartphone-Fotos).");
        }
        String actor = user != null ? user.email() : "system";
        GeoPhotoEntity photo = photoService.store(file, actor);
        // Directly read the EXIF metadata — the workflow is upload → Geodaten
        // auslesen → Ergebnis.
        ExtractedGps gps = photoService.extract(photo.getStoragePath());
        if (gps.hasGps()) {
            photo.setGpsSource("EXIF");
        }
        photoService.applyMetadata(photo, gps);
        // EXIF-Daten sind ausgelesen und im Datensatz; die Anzeige-/Download-
        // Kopie wird bereinigt (kein Gerät/Firmware in der Datei). Scheitert
        // die Bereinigung, bleibt die Originaldatei als Anzeige-Kopie.
        photo.setOriginalPath(photo.getStoragePath());
        try {
            photo.setStoragePath(photoService.createSanitizedCopy(
                    photoService.resolve(photo.getStoragePath()), photo.getContentType()));
        } catch (Exception e) {
            log.warn("Sanitized copy failed for {}: {}", photo.getOriginalName(), e.getMessage());
        }
        photoRepository.save(photo);
        log.info("Geo-Foto hochgeladen: {} (GPS: {})", photo.getOriginalName(), gps.hasGps() ? "ja" : "nein");
        return "redirect:/geoinformation/photos";
    }

    /** "Geodaten aus Bild auslesen" — re-reads the EXIF metadata from the stored image. */
    @PostMapping("/geoinformation/photos/{id}/extract")
    public String extractPhoto(@PathVariable UUID id,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        GeoPhotoEntity photo = photoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht gefunden"));
        if (user == null || !photo.getUploadedBy().equals(user.email())
                && (user.roles() == null || !user.roles().contains("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String sourcePath = photo.getOriginalPath() != null ? photo.getOriginalPath() : photo.getStoragePath();
        ExtractedGps gps = photoService.extract(sourcePath);
        if (gps.hasGps()) {
            photo.setGpsSource("EXIF");
        }
        photoService.applyMetadata(photo, gps);
        return "redirect:/geoinformation/photos";
    }

    /** Manuelle Standortzuweisung über die Karte (Bild ohne GPS-Metadaten). */
    @PostMapping("/geoinformation/photos/{id}/set-location")
    public String setPhotoLocation(@PathVariable UUID id,
                                   @RequestParam("lat") double lat,
                                   @RequestParam("lon") double lon,
                                   @RequestParam(value = "capturedAt", required = false) String capturedAt,
                                   @AuthenticationPrincipal AuthenticatedUser user) {
        denyDemoAdmin(user);
        GeoPhotoEntity photo = photoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht gefunden"));
        if (user == null || !photo.getUploadedBy().equals(user.email())
                && (user.roles() == null || !user.roles().contains("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        photo.setLatitude(lat);
        photo.setLongitude(lon);
        photo.setHasGeo(true);
        photo.setGpsSource("MANUAL");
        if (capturedAt != null && !capturedAt.isBlank()) {
            try {
                photo.setCapturedAt(Instant.parse(capturedAt));
            } catch (Exception ignored) {
                // Aufnahmedatum bleibt unbekannt
            }
        }
        photoRepository.save(photo);
        return "redirect:/geoinformation/photos";
    }

    /**
     * Serves the stored photo (auth-protected like cases/documents).
     * Default: inline (Anzeige im Browser/Modal). Mit {@code download=1} wird
     * explizit als Dateianhang ausgeliefert („Original herunterladen").
     */
    @GetMapping("/geoinformation/photos/{id}/image")
    public ResponseEntity<byte[]> photoImage(@PathVariable UUID id,
                                             @RequestParam(value = "download", required = false) boolean download,
                                             @AuthenticationPrincipal AuthenticatedUser user) {
        GeoPhotoEntity photo = photoRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht gefunden"));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!GeoPhotoService.isSupportedContentType(photo.getContentType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Foto");
        }
        if (photo.getWorkspaceId() != null) {
            // Once attached to a case, the photo follows the case access rules.
            caseAccessGuard.requireAccess(photo.getWorkspaceId().toString(), user);
        } else if (!photo.getUploadedBy().equals(user.email())
                && (user.roles() == null || !user.roles().contains("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        // Fehlt die lokale Anzeige-Kopie (z. B. nach geleertem Laufzeit-
        // Upload-Verzeichnis), wird sie deterministisch aus dem gebündelten
        // Demo-Asset wiederhergestellt — nie „Bild nicht anzeigbar" für
        // vorhandene Demo-Fotos.
        Path file = photoService.ensureLocalImage(photo);
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht lesbar");
        }
        try {
            String fileName = photo.getOriginalName() != null
                    ? photo.getOriginalName().replaceAll("[\\r\\n\\\"]", "_") : "foto.jpg";
            String disposition = download
                    ? "attachment; filename=\"" + fileName
                    + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                    : "inline";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(photo.getContentType() != null
                            ? photo.getContentType() : "image/jpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .body(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht lesbar");
        }
    }

    // ── Geovorgänge ────────────────────────────────────────────────────────

    /** Ein Mitarbeiter-Eintrag für die Zuweisungs-Liste. */
    public record Mitarbeiter(String email, String displayName, String department) {}

    /** Liste aller aktiven Mitarbeiterkonten (für die Zuweisung des Geovorgangs). */
    private List<Mitarbeiter> mitarbeiterList() {
        try {
            return userAccountRepository.findAll().stream()
                    .filter(u -> u.isEnabled() && !u.isLocked())
                    .map(u -> new Mitarbeiter(u.getEmail(),
                            u.getDisplayName() != null && !u.getDisplayName().isBlank()
                                    ? u.getDisplayName() : u.getEmail(),
                            u.getDepartment()))
                    .sorted(Comparator.comparing(Mitarbeiter::displayName))
                    .toList();
        } catch (Exception e) {
            log.warn("Mitarbeiterliste nicht ladbar: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Formular für einen NEUEN, bearbeitbaren Geovorgang (kein vorbefüllter
     * Detailfall): Koordinaten + Adresse können von der Karte übernommen oder
     * frei eingegeben werden; Status, Kategorie, Priorität, Bemerkung,
     * verantwortliche Person und ein optionales Foto sind editierbar.
     */
    @GetMapping("/geoinformation/geo-cases/new")
    public String newGeoCaseForm(@RequestParam(value = "lat", required = false) Double lat,
                                 @RequestParam(value = "lon", required = false) Double lon,
                                 @AuthenticationPrincipal AuthenticatedUser user,
                                 Model model) {
        addChrome(model, "Neuer Geovorgang");
        model.addAttribute("geoActive", "geo-cases");
        model.addAttribute("demoAddresses", geoService.addresses());
        model.addAttribute("geoCategories", GEO_CATEGORIES);
        model.addAttribute("geoPriorities", List.of("NIEDRIG", "MITTEL", "HOCH"));
        model.addAttribute("geoStatuses", List.of(
                new StatusOption("DRAFT", "Entwurf"),
                new StatusOption("ACTIVE", "In Bearbeitung"),
                new StatusOption("CLOSED", "Abgeschlossen")));
        model.addAttribute("mitarbeiter", mitarbeiterList());
        model.addAttribute("currentUser", user != null ? user.email() : "");
        model.addAttribute("currentUserName", user != null && user.displayName() != null
                ? user.displayName() : (user != null ? user.email() : ""));
        model.addAttribute("workspaceCode", "WS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        model.addAttribute("createdAt", DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
                .format(java.time.LocalDateTime.now()));
        if (lat != null && lon != null) {
            model.addAttribute("lat", lat);
            model.addAttribute("lon", lon);
            GeoService.GeoAddress nearest = geoService.nearestAddress(lat, lon, 1500).orElse(null);
            model.addAttribute("address", nearest != null ? nearest.fullAddress() : "");
            model.addAttribute("street", nearest != null ? nearest.street() : "");
            model.addAttribute("houseNumber", nearest != null ? nearest.houseNumber() : "");
            model.addAttribute("postalCode", nearest != null ? nearest.postalCode() : "");
            model.addAttribute("city", nearest != null ? nearest.city() : "");
            Jurisdiction j = geoService.jurisdictionFor(lat, lon);
            model.addAttribute("geoDistrict", j.resolved() ? j.district() : "nicht bestimmbar");
            model.addAttribute("geoAuthority", j.resolved() ? j.authority() : "nicht bestimmbar");
        }
        return "geoinformation/geo-case-new";
    }

    /** Anzeigename eines Kontos (aus den echten Stammdaten), sonst die E-Mail. */
    private String ownerDisplayName(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return "Nicht zugewiesen";
        }
        try {
            return userAccountRepository.findByEmail(ownerId.toLowerCase())
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : ownerId)
                    .orElse(ownerId);
        } catch (Exception e) {
            return ownerId;
        }
    }

    public record StatusOption(String value, String label) {}

    /**
     * Legt einen Geovorgang aus dem Formular an (ohne Foto möglich). Koordinaten
     * und Adresse stammen aus der Kartenauswahl oder manueller Eingabe; die
     * Zuständigkeit wird deterministisch aus den Koordinaten abgeleitet.
     * Erstellungs- und Statusereignisse werden in der Timeline festgehalten.
     */
    @PostMapping("/geoinformation/geo-cases/create")
    public String createGeoCaseFromForm(
            @RequestParam("name") String name,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "street", required = false) String street,
            @RequestParam(value = "houseNumber", required = false) String houseNumber,
            @RequestParam(value = "postalCode", required = false) String postalCode,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "remark", required = false) String remark,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        denyDemoAdmin(user);
        if (lat == null || lon == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bitte zuerst einen Standort auf der Karte auswählen oder die Koordinaten eingeben.");
        }
        String title = name != null && !name.isBlank() ? name.trim() : "Geovorgang " + address;
        WorkspaceEntity ws = workspaceService.createWorkspace(new CreateWorkspaceCommand(
                title, "Geovorgang (manuell angelegt).", "GEO", user.email()));
        ws.setGeoAddress(address != null && !address.isBlank() ? address.trim() : null);
        ws.setGeoStreet(street != null && !street.isBlank() ? street.trim() : null);
        ws.setGeoHouseNumber(houseNumber != null && !houseNumber.isBlank() ? houseNumber.trim() : null);
        ws.setGeoPostalCode(postalCode != null && !postalCode.isBlank() ? postalCode.trim() : null);
        ws.setGeoCity(city != null && !city.isBlank() ? city.trim() : null);
        ws.setGeoLatitude(lat);
        ws.setGeoLongitude(lon);
        Jurisdiction j = geoService.jurisdictionFor(lat, lon);
        ws.setGeoDistrict(j.resolved() ? j.district() : null);
        ws.setGeoAuthority(j.resolved() ? j.authority() : null);
        if (status != null && !status.isBlank()) {
            try {
                ws.setStatus(reasoning.common.model.WorkspaceStatus.valueOf(status));
            } catch (IllegalArgumentException ignored) {
                // ungültiger Status → bleibt Entwurf
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        String normalizedCategory = category != null && !category.isBlank() ? category.trim() : "Geovorgang";
        data.put("caseCategory", normalizedCategory);
        if (category != null && !category.isBlank()) data.put("geoCategory", category.trim());
        if (priority != null && !priority.isBlank()) data.put("geoPriority", priority.trim());
        if (remark != null && !remark.isBlank()) data.put("geoRemark", remark.trim());
        data.put("source", "Kartenauswahl");
        data.put("gpsSource", "Kartenauswahl / Manuell auf Karte ausgewählt");
        try {
            ws.setPhaseData(mapper.writeValueAsString(data));
        } catch (Exception ignored) {
        }
        workspaceService.save(ws);

        // Timeline: Erstellung + ggf. Zuweisung + ggf. Statusänderung
        try {
            workspaceService.addTimelineEvent(ws.getId().toString(), java.time.LocalDate.now(),
                    "Geovorgang erstellt",
                    "Erstellt von " + user.email() + " (Kartenauswahl)",
                    reasoning.workspace.model.TimelineEventType.EVENT,
                    null, 1.0, false);
            if (assignee != null && !assignee.isBlank() && !assignee.equals(user.email())) {
                workspaceService.addTimelineEvent(ws.getId().toString(), java.time.LocalDate.now(),
                        "Zugewiesen an " + assignee,
                        "Geovorgang wurde von " + user.email() + " an " + assignee + " übergeben.",
                        reasoning.workspace.model.TimelineEventType.CHANGE,
                        null, 1.0, false);
            }
            if ("ACTIVE".equals(status)) {
                workspaceService.addTimelineEvent(ws.getId().toString(), java.time.LocalDate.now(),
                        "Status geändert: Entwurf → In Bearbeitung",
                        "Statusänderung durch " + user.email() + ".",
                        reasoning.workspace.model.TimelineEventType.CHANGE,
                        null, 1.0, false);
            }
        } catch (Exception e) {
            log.warn("Timeline-Ereignisse konnten nicht angelegt werden: {}", e.getMessage());
        }

        // Optionales Foto: hochladen, EXIF lesen, als Beleg anhängen.
        if (photo != null && !photo.isEmpty() && GeoPhotoService.isSupportedContentType(photo.getContentType())) {
            try {
                GeoPhotoEntity stored = photoService.store(photo, user.email());
                var gps = photoService.extract(stored.getStoragePath());
                photoService.applyMetadata(stored, gps);
                stored.setWorkspaceId(UUID.fromString(ws.getId()));
                photoRepository.save(stored);
                attachPhotoAsDocument(ws, stored, user.email());
            } catch (Exception e) {
                log.warn("Foto zum Geovorgang konnte nicht angehängt werden: {}", e.getMessage());
            }
        }
        log.info("Geovorgang {} manuell angelegt (Gebiet: {})", ws.getWorkspaceCode(),
                j.resolved() ? j.district() : "unbekannt");
        return "redirect:/geoinformation/geo-cases/" + ws.getId();
    }

    /** Ändert den Status eines bestehenden Geovorgangs und protokolliert die Änderung. */
    @PostMapping("/geoinformation/geo-cases/{id}/status")
    public String changeGeoCaseStatus(@PathVariable String id,
                                      @RequestParam("status") String status,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        // Leitungs-Konto ist schreibgeschützt; operative Statusänderungen nur
        // durch die zuständige Mitarbeiterin (eigene Vorgänge + Arbeitspool).
        WorkspaceEntity ws = caseAccessGuard.requireWriteAccess(id, user);
        try {
            reasoning.common.model.WorkspaceStatus newStatus =
                    reasoning.common.model.WorkspaceStatus.valueOf(status);
            // Archivieren = Ausblenden/Löschen aus dem aktiven GEO-Bestand:
            // für KEINE Anwendungsrolle erlaubt (nur Demo-Reset).
            if (newStatus == reasoning.common.model.WorkspaceStatus.ARCHIVED) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Geovorgänge können nicht archiviert werden — der Demo-Reset ist der einzige Weg, GEO-Demo-Bestände zu entfernen.");
            }
            String oldLabel = CaseController.statusLabel(ws.getStatus());
            ws.setStatus(newStatus);
            workspaceService.save(ws);
            workspaceService.addTimelineEvent(id, java.time.LocalDate.now(),
                    "Status geändert: " + oldLabel + " → " + CaseController.statusLabel(newStatus),
                    "Statusänderung durch " + (user != null ? user.email() : "unbekannt") + ".",
                    reasoning.workspace.model.TimelineEventType.CHANGE,
                    null, 1.0, false);
        } catch (IllegalArgumentException e) {
            log.warn("Ungültiger Status {} für Geovorgang {}", status, id);
        }
        return "redirect:/geoinformation/geo-cases/" + id;
    }

    @GetMapping("/geoinformation/geo-cases/{id}")
    public String geoCaseDetail(@PathVariable String id,
                                @AuthenticationPrincipal AuthenticatedUser user,
                                Model model) {
        WorkspaceEntity ws = caseAccessGuard.requireAccess(id, user);
        addChrome(model, "Geovorgang " + ws.getWorkspaceCode());
        model.addAttribute("geoActive", "geo-cases");
        model.addAttribute("caseId", id);
        model.addAttribute("demoAddresses", geoService.addresses());
        model.addAttribute("caseName", ws.getName() != null ? ws.getName() : ws.getWorkspaceCode());
        model.addAttribute("caseCode", ws.getWorkspaceCode());
        model.addAttribute("caseDescription", ws.getDescription());
        model.addAttribute("caseStatus", CaseController.statusLabel(ws.getStatus()));
        model.addAttribute("caseOwner", ws.getOwnerId());
        // Anzeigename der zuständigen Mitarbeiterin (echtes Konto), sonst die
        // E-Mail-Adresse — nie ein technischer Pool-Marker.
        model.addAttribute("caseOwnerName", ownerDisplayName(ws.getOwnerId()));
        // Adresse nachlösen, wenn der Vorgang Koordinaten hat, aber keine
        // Adresse gespeichert wurde (z. B. ältere Fälle aus Foto-Koordinaten):
        // die Adresse wird dann beim Öffnen aus dem lokalen Adressbestand
        // rückgeocodiert — nie leer lassen, wenn der Bestand sie hergibt.
        String address = ws.getGeoAddress();
        String street = ws.getGeoStreet();
        String houseNumber = ws.getGeoHouseNumber();
        String postalCode = ws.getGeoPostalCode();
        String city = ws.getGeoCity();
        if ((address == null || address.isBlank())
                && ws.getGeoLatitude() != null && ws.getGeoLongitude() != null) {
            GeoService.GeoAddress nearest = geoService.nearestAddress(
                    ws.getGeoLatitude(), ws.getGeoLongitude(), 1500).orElse(null);
            if (nearest != null) {
                address = nearest.fullAddress();
                street = nearest.street();
                houseNumber = nearest.houseNumber();
                postalCode = nearest.postalCode();
                city = nearest.city();
            }
        }
        model.addAttribute("geoAddress", address);
        model.addAttribute("geoStreet", street);
        model.addAttribute("geoHouseNumber", houseNumber);
        model.addAttribute("geoPostalCode", postalCode);
        model.addAttribute("geoCity", city);
        model.addAttribute("geoLatitude", ws.getGeoLatitude());
        model.addAttribute("geoLongitude", ws.getGeoLongitude());
        model.addAttribute("geoDistrict", ws.getGeoDistrict());
        model.addAttribute("geoAuthority", ws.getGeoAuthority());
        model.addAttribute("geoCreatedAt", ws.getCreatedAt() != null
                ? DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
                        .format(ws.getCreatedAt().atZone(java.time.ZoneId.systemDefault())) : "—");
        // Manuelle Fallangaben (phaseData) — normale Fall-Informationen.
        Map<String, Object> phaseData = ws.getPhaseDataMap();
        model.addAttribute("geoCategory", phaseData.get("geoCategory"));
        model.addAttribute("geoPriority", phaseData.get("geoPriority"));
        model.addAttribute("geoRemark", phaseData.get("geoRemark"));
        // Das zugehörige Foto (Beleg), falls vorhanden.
        GeoPhotoEntity photo = photoRepository.findAll().stream()
                .filter(p -> p.getWorkspaceId() != null && p.getWorkspaceId().toString().equals(id))
                .findFirst().orElse(null);
        model.addAttribute("photo", photo);
        if (photo != null) {
            model.addAttribute("photoCapturedAt", GeoPhotoService.formatCapturedAt(photo.getCapturedAt()));
            model.addAttribute("photoGpsSource", photo.getGpsSource());
        }
        // Bearbeitungshistorie (Mitarbeitende, Statusänderungen, Zuweisungen).
        try {
            model.addAttribute("timeline", caseTimelineService.build(id));
        } catch (Exception e) {
            model.addAttribute("timeline", List.of());
        }
        return "geoinformation/geo-case-detail";
    }

    /**
     * Creates a Geovorgang from a photo: an ordinary workspace (type GEO) with
     * structured geographic context; the photo becomes a document attached as
     * evidence. Fully deterministic — the district is derived from the
     * coordinates via the jurisdiction rule.
     */
    @PostMapping("/geoinformation/geo-cases")
    public String createGeoCase(@RequestParam("photoId") UUID photoId,
                                @RequestParam("name") String name,
                                @RequestParam(value = "category", required = false) String category,
                                @RequestParam(value = "description", required = false) String description,
                                @RequestParam(value = "priority", required = false) String priority,
                                @RequestParam(value = "remark", required = false) String remark,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        denyDemoAdmin(user);
        GeoPhotoEntity photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Foto nicht gefunden"));
        if (!photo.isHasGeo() || photo.getLatitude() == null || photo.getLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dem Foto fehlen Geokoordinaten. Bitte zuerst einen Standort ermitteln oder auswählen.");
        }
        String title = name != null && !name.isBlank() ? name.trim()
                : "Geovorgang " + photo.getOriginalName();
        WorkspaceEntity ws = workspaceService.createWorkspace(
                new CreateWorkspaceCommand(title,
                        description != null ? description.trim() : "Geovorgang aus Feldaufnahme.",
                        "GEO", user.email()));

        // Strukturierter geografischer Kontext — Zuständigkeitsgebiet und
        // zuständige Stelle deterministisch aus den Koordinaten abgeleitet.
        // Die Adresse wird aus den Foto-Koordinaten rückgeocodiert (lokaler
        // Adressbestand), nie leer gelassen, wenn der Bestand sie hergibt.
        Jurisdiction j = geoService.jurisdictionFor(photo.getLatitude(), photo.getLongitude());
        GeoService.GeoAddress nearest = geoService.nearestAddress(
                photo.getLatitude(), photo.getLongitude(), 1500).orElse(null);
        ws.setGeoAddress(nearest != null ? nearest.fullAddress() : null);
        ws.setGeoStreet(nearest != null ? nearest.street() : null);
        ws.setGeoHouseNumber(nearest != null ? nearest.houseNumber() : null);
        ws.setGeoPostalCode(nearest != null ? nearest.postalCode() : null);
        ws.setGeoCity(nearest != null ? nearest.city() : null);
        ws.setGeoLatitude(photo.getLatitude());
        ws.setGeoLongitude(photo.getLongitude());
        ws.setGeoDistrict(j.resolved() ? j.district() : null);
        ws.setGeoAuthority(j.resolved() ? j.authority() : null);
        // Manuelle Fallangaben als normale Fall-Informationen (phaseData, wie
        // "source"/"citizen" bei E-Mail-Fällen) — keine parallele Falllogik.
        Map<String, Object> data = new LinkedHashMap<>();
        String normalizedCategory = category != null && !category.isBlank() ? category.trim() : "Geovorgang";
        data.put("caseCategory", normalizedCategory);
        if (category != null && !category.isBlank()) data.put("geoCategory", category.trim());
        if (priority != null && !priority.isBlank()) data.put("geoPriority", priority.trim());
        if (remark != null && !remark.isBlank()) data.put("geoRemark", remark.trim());
        try {
            ws.setPhaseData(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
        } catch (Exception ignored) {
            // Fall bleibt ohne Zusatzangaben gültig
        }
        workspaceService.save(ws);
        photo.setWorkspaceId(UUID.fromString(ws.getId()));
        photoRepository.save(photo);

        // Foto als Beleg-Dokument anhängen (bestehender Dokument-Mechanismus).
        attachPhotoAsDocument(ws, photo, user.email());
        log.info("Geovorgang {} angelegt aus Foto {} (Gebiet: {})", ws.getWorkspaceCode(),
                photo.getOriginalName(), j.resolved() ? j.district() : "unbekannt");
        return "redirect:/geoinformation/geo-cases/" + ws.getId();
    }

    private void attachPhotoAsDocument(WorkspaceEntity ws, GeoPhotoEntity photo, String actorEmail) {
        try {
            Path file = photoService.resolve(photo.getStoragePath());
            DocumentFileType fileType = photo.getOriginalName() != null
                    && photo.getOriginalName().toLowerCase().endsWith(".png") ? DocumentFileType.PNG : DocumentFileType.JPG;
            byte[] bytes = Files.readAllBytes(file);
            String checksum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).toString();
            var cmd = new CreateDocumentCommand(
                    "Foto " + photo.getOriginalName(), fileType, photo.getOriginalName(),
                    photo.getContentType(), bytes.length, "local", photo.getStoragePath(),
                    checksum, "OTHER", java.util.Set.of("geo"),
                    "INTERNAL", actorEmail, "default");
            var doc = documentFacade.createDocument(cmd);
            workspaceService.attachDocument(new AttachDocumentCommand(
                    ws.getId().toString(), doc.id().toString(),
                    DocumentCategory.OTHER, "OTHER", null));
            log.info("Foto {} als Beleg-Dokument {} an Vorgang {} angehängt",
                    photo.getOriginalName(), doc.id(), ws.getWorkspaceCode());
        } catch (Exception e) {
            log.warn("Foto konnte nicht als Dokument angehängt werden: {}", e.getMessage());
        }
    }

    private void addChrome(Model model, String title) {
        model.addAttribute("pageTitle", "Geoinformation — " + title);
        model.addAttribute("activeSection", "geoinformation");
        model.addAttribute("breadcrumbs", List.of(
                new HomeController.Breadcrumb("Home", "/dashboard"),
                new HomeController.Breadcrumb("Geoinformation", "/geoinformation"),
                new HomeController.Breadcrumb(title, null)));
    }
}
