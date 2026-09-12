package verwaltungsassistent.web.controller;

import reasoning.auth.api.AuthenticatedUser;
import reasoning.common.model.DocumentStatus;
import reasoning.document.application.DocumentService;
import reasoning.document.model.Document;
import reasoning.workspace.api.WorkspaceEntity;
import reasoning.workspace.application.WorkspaceService;
import verwaltungsassistent.web.geo.GeoPhotoEntity;
import verwaltungsassistent.web.geo.GeoPhotoRepository;
import verwaltungsassistent.web.geo.GeoPhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused controller tests for the Geoinformation module: deterministic
 * jurisdiction endpoints, geo-case creation with derived district, and
 * photo/case authorization. GeoService (lookup + point-in-polygon) is real;
 * persistence and document facades are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GeoPhotoRepository photoRepository;

    @MockBean
    private GeoPhotoService photoService;

    @MockBean
    private WorkspaceService workspaceService;

    // Mock the concrete implementation so DocumentIngestionWorker still finds
    // a DocumentService bean (mocking the interface would remove it entirely).
    @MockBean
    private DocumentService documentService;

    @TempDir
    Path tempDir;

    private final AuthenticatedUser user = new AuthenticatedUser(
            UUID.randomUUID(), "user@example.com", "Test User", Set.of("USER"));
    private final AuthenticatedUser other = new AuthenticatedUser(
            UUID.randomUUID(), "other@example.com", "Other User", Set.of("USER"));

    // Real OSM coordinate: Potsdam (Landeshauptstadt Potsdam)
    private static final double KA85_LAT = 52.390569;
    private static final double KA85_LON = 13.064473;
    // Real OSM coordinate: Werder (Havel), Landkreis Potsdam-Mittelmark
    private static final double WERDER_LAT = 52.378050;
    private static final double WERDER_LON = 12.933200;

    private GeoPhotoEntity photoWithGps;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        photoWithGps = new GeoPhotoEntity(UUID.randomUUID(), "werder.jpg",
                "geo/demo.jpg", "image/jpeg", user.email(), Instant.now());
        photoWithGps.setLatitude(KA85_LAT);
        photoWithGps.setLongitude(KA85_LON);
        photoWithGps.setHasGeo(true);
    }

    // ── Deterministic API ──────────────────────────────────────────────────

    @Test
    void addressSearch_knownRealAddress_resolvesDistrictDeterministically() throws Exception {
        mockMvc.perform(get("/geoinformation/api/addresses").param("q", "Aalweg 6, 14542 Werder (Havel)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.results[0].street", is("Aalweg")))
                .andExpect(jsonPath("$.results[0].houseNumber", is("6")))
                .andExpect(jsonPath("$.results[0].postalCode", is("14542")))
                .andExpect(jsonPath("$.results[0].jurisdiction.resolved", is(true)))
                .andExpect(jsonPath("$.results[0].jurisdiction.district", is("Potsdam-Mittelmark")));
    }

    @Test
    void addressSearch_exactHouseNumber_reportedHonestly() throws Exception {
        // exakte Hausnummer vorhanden -> exactMatch = true, genau 1 Treffer
        mockMvc.perform(get("/geoinformation/api/addresses").param("q", "Aalweg 6, 14542 Werder (Havel)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.exactMatch", is(true)));

        // Hausnummer 17 existiert nicht im lokalen Bestand -> ehrlich ähnliche Treffer
        mockMvc.perform(get("/geoinformation/api/addresses").param("q", "Aalweg 17, 14542 Werder (Havel)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactMatch", is(false)))
                .andExpect(jsonPath("$.count", is(1)))
                .andExpect(jsonPath("$.results[0].houseNumber", is("6")));
    }

    @Test
    void jurisdiction_knownCoordinate_isDeterministicAndResolved() throws Exception {
        mockMvc.perform(get("/geoinformation/api/jurisdiction")
                        .param("lat", String.valueOf(KA85_LAT)).param("lon", String.valueOf(KA85_LON)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved", is(true)))
                .andExpect(jsonPath("$.district", is("Potsdam")))
                .andExpect(jsonPath("$.authority", is("Landeshauptstadt Potsdam")))
                .andExpect(jsonPath("$.deterministic", is(true)))
                .andExpect(jsonPath("$.method", org.hamcrest.Matchers.containsString("Punkt-in-Polygon")));
    }

    @Test
    void jurisdiction_coordinateOutsideBrandenburg_isUnresolved() throws Exception {
        mockMvc.perform(get("/geoinformation/api/jurisdiction")
                        .param("lat", "53.55").param("lon", "9.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved", is(false)))
                .andExpect(jsonPath("$.district").doesNotExist());
    }

    @Test
    void bezirkeGeoJson_servedLocally() throws Exception {
        mockMvc.perform(get("/geoinformation/api/bezirke"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/geo+json"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FeatureCollection")));
    }

    // ── Geovorgang creation ────────────────────────────────────────────────

    @Test
    void createGeoCase_derivesDistrictAndAuthorityFromCoordinates() throws Exception {
        WorkspaceEntity created = new WorkspaceEntity("GV-TEST", "Müllablagerung",
                "Demo", "GEO", user.email());
        when(photoRepository.findById(photoWithGps.getId())).thenReturn(Optional.of(photoWithGps));
        when(workspaceService.createWorkspace(any())).thenReturn(created);
        when(documentService.createDocument(any())).thenReturn(new Document(
                UUID.randomUUID(), "tenant", null, DocumentStatus.READY, 1,
                user.email(), user.email(), Instant.now(), Instant.now(),
                null, null, null, null, List.of()));

        mockMvc.perform(post("/geoinformation/geo-cases")
                        .param("photoId", photoWithGps.getId().toString())
                        .param("name", "Müllablagerung")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/geoinformation/geo-cases/**"));

        var captor = org.mockito.ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).save(captor.capture());
        WorkspaceEntity saved = captor.getValue();
        assert saved.getGeoDistrict() != null && saved.getGeoDistrict().equals("Potsdam")
                : "erwartetes Zuständigkeitsgebiet Potsdam, war " + saved.getGeoDistrict();
        assert saved.getGeoAuthority() != null
                && saved.getGeoAuthority().equals("Landeshauptstadt Potsdam")
                : "erwartete Behörde, war " + saved.getGeoAuthority();
        assert saved.getGeoLatitude() != null && Math.abs(saved.getGeoLatitude() - KA85_LAT) < 0.0001;
        assert saved.getGeoLongitude() != null && Math.abs(saved.getGeoLongitude() - KA85_LON) < 0.0001;
        assert saved.getGeoCity() == null
                : "Stadt wird für Foto-Geovorgänge nicht geraten, war " + saved.getGeoCity();
        assert photoWithGps.getWorkspaceId() != null
                && photoWithGps.getWorkspaceId().toString().equals(created.getId());
    }

    @Test
    void createGeoCase_persistsCategoryPriorityRemarkAsNormalCaseInfo() throws Exception {
        WorkspaceEntity created = new WorkspaceEntity("GV-TEST2", "Defekte Laterne",
                "Demo", "GEO", user.email());
        when(photoRepository.findById(photoWithGps.getId())).thenReturn(Optional.of(photoWithGps));
        when(workspaceService.createWorkspace(any())).thenReturn(created);

        mockMvc.perform(post("/geoinformation/geo-cases")
                        .param("photoId", photoWithGps.getId().toString())
                        .param("name", "Defekte Laterne")
                        .param("category", "Straßenbeleuchtung")
                        .param("priority", "HOCH")
                        .param("remark", "Mast gegenüber Hausnummer 32")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        var captor = org.mockito.ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceService).save(captor.capture());
        WorkspaceEntity saved = captor.getValue();
        assert saved.getPhaseData().contains("\"geoCategory\":\"Straßenbeleuchtung\"")
                : "Kategorie fehlt: " + saved.getPhaseData();
        assert saved.getPhaseData().contains("\"geoPriority\":\"HOCH\"");
        assert saved.getPhaseData().contains("\"geoRemark\":\"Mast gegenüber Hausnummer 32\"");
    }

    @Test
    void createGeoCase_photoWithoutGeo_rejected() throws Exception {
        GeoPhotoEntity noGps = new GeoPhotoEntity(UUID.randomUUID(), "ohne.jpg",
                "geo/ohne.jpg", "image/jpeg", user.email(), Instant.now());
        when(photoRepository.findById(noGps.getId())).thenReturn(Optional.of(noGps));

        mockMvc.perform(post("/geoinformation/geo-cases")
                        .param("photoId", noGps.getId().toString())
                        .param("name", "Ohne Geodaten")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // ── Geo case detail + authorization ────────────────────────────────────

    @Test
    void geoCaseDetail_owner_seesDistrictAndPhotoSection() throws Exception {
        String caseId = UUID.randomUUID().toString();
        WorkspaceEntity entity = new WorkspaceEntity("GV-1", "Müll am Ufer",
                "Demo", "GEO", user.email());
        entity.setGeoAddress("Aalweg 6, 14542 Werder (Havel)");
        entity.setGeoLatitude(WERDER_LAT);
        entity.setGeoLongitude(WERDER_LON);
        entity.setGeoDistrict("Potsdam-Mittelmark");
        entity.setGeoAuthority("Landkreis Potsdam-Mittelmark");
        entity.setPhaseData("{\"geoCategory\":\"Müllablagerung\",\"geoPriority\":\"HOCH\",\"geoRemark\":\"Am Straßenbaum\"}");
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(photoRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/geoinformation/geo-cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Potsdam-Mittelmark")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Aalweg 6")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Zuständige Behörde")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vorgang bearbeiten")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Kategorie: Müllablagerung")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Priorität: Hoch")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Bemerkung: Am Straßenbaum")));
    }

    @Test
    void nearby_knownCoordinate_listsRealAddressesWithinRadius() throws Exception {
        mockMvc.perform(get("/geoinformation/api/nearby")
                        .param("lat", String.valueOf(WERDER_LAT)).param("lon", String.valueOf(WERDER_LON))
                        .param("radius", "3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.results[0].distanceMeters",
                        org.hamcrest.Matchers.lessThanOrEqualTo(3000)));
    }

    @Test
    void photosView_rendersJurisdictionCardPerPhoto() throws Exception {
        // Geotagged Fotos = gemeinsames Außendienst-Register: die Foto-Seite
        // liest das Register (photoRepository.findAll), nicht nur eigene Fotos.
        when(photoRepository.findAll()).thenReturn(List.of(photoWithGps));
        when(photoService.isSupportedPhoto(photoWithGps)).thenReturn(true);

        mockMvc.perform(get("/geoinformation/photos"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Geodaten erkannt")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Zuständige Behörde: Landeshauptstadt Potsdam")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Zuständigkeitsgebiet: Potsdam")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("JPG/JPEG und PNG")));
    }

    @Test
    void geoCaseDetail_otherEmployee_canReadGeoRegister() throws Exception {
        // Geovorgänge sind das gemeinsame Außendienst-Register: Jede
        // Mitarbeiterin darf einen Geovorgang LESEN (Liste, Karte, Fotos).
        // Schreibaktionen (Status etc.) bleiben über requireWriteAccess an
        // owner/Arbeitspool gebunden; Löschen/Archivieren bleibt für GEO
        // für alle Rollen gesperrt (requireNotGeo).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        other, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        String caseId = UUID.randomUUID().toString();
        WorkspaceEntity entity = new WorkspaceEntity("GV-1", "Müll am Ufer",
                "Demo", "GEO", user.email());
        when(workspaceService.findById(caseId)).thenReturn(Optional.of(entity));
        when(photoRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/geoinformation/geo-cases/" + caseId))
                .andExpect(status().isOk());
    }

    // ── Photo upload + image serving ───────────────────────────────────────

    @Test
    void uploadPhoto_redirectsToListing() throws Exception {
        when(photoService.store(any(), anyString())).thenReturn(photoWithGps);
        when(photoService.extract(anyString())).thenReturn(
                new GeoPhotoService.ExtractedGps(52.5420, 13.4100, Instant.now(), null, true));
        when(photoService.applyMetadata(any(), any())).thenReturn(photoWithGps);

        mockMvc.perform(multipart("/geoinformation/photos/upload")
                        .file(new MockMultipartFile("file", "foto.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/geoinformation/photos"));
    }

    @Test
    void photoImage_ownPhoto_served() throws Exception {
        Path file = tempDir.resolve("foto.jpg");
        Files.write(file, new byte[]{1, 2, 3});
        when(photoRepository.findById(photoWithGps.getId())).thenReturn(Optional.of(photoWithGps));
        when(photoService.resolve(anyString())).thenReturn(file);

        mockMvc.perform(get("/geoinformation/photos/{id}/image", photoWithGps.getId()))
                .andExpect(status().isOk())
                .andExpect(header().string(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")));
    }

    @Test
    void photoImage_downloadParam_returnsAttachment() throws Exception {
        Path file = tempDir.resolve("foto.jpg");
        Files.write(file, new byte[]{1, 2, 3});
        when(photoRepository.findById(photoWithGps.getId())).thenReturn(Optional.of(photoWithGps));
        when(photoService.resolve(anyString())).thenReturn(file);

        mockMvc.perform(get("/geoinformation/photos/{id}/image", photoWithGps.getId())
                        .param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")));
    }

    @Test
    void uploadPhoto_unsupportedFormat_rejected() throws Exception {
        mockMvc.perform(multipart("/geoinformation/photos/upload")
                        .file(new MockMultipartFile("file", "foto.tiff", "image/tiff", new byte[]{1, 2, 3}))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(photoService, org.mockito.Mockito.never()).store(any(), anyString());
    }

    @Test
    void photoImage_foreignPhoto_forbidden() throws Exception {
        GeoPhotoEntity foreign = new GeoPhotoEntity(UUID.randomUUID(), "fremd.jpg",
                "geo/fremd.jpg", "image/jpeg", other.email(), Instant.now());
        when(photoRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        mockMvc.perform(get("/geoinformation/photos/{id}/image", foreign.getId()))
                .andExpect(status().isForbidden());
    }
}
