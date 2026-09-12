package verwaltungsassistent.web.service;

import verwaltungsassistent.web.service.GeoService.GeoAddress;
import verwaltungsassistent.web.service.GeoService.Jurisdiction;
import verwaltungsassistent.web.service.GeoService.Verwaltungsgebiet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused tests for the deterministic geographic foundation (Brandenburg boundaries + real addresses). */
class GeoServiceTest {

    private final GeoService geo = new GeoService();

    // Real addresses in the Brandenburger Demo-Orte
    private static final double POTSDAM_LAT = 52.390569;
    private static final double POTSDAM_LON = 13.064473;
    private static final double ORANIENBURG_LAT = 52.753100;
    private static final double ORANIENBURG_LON = 13.241900;
    private static final double BRB_LAT = 52.412500;
    private static final double BRB_LON = 12.549700;
    private static final double WERDER_LAT = 52.378050;
    private static final double WERDER_LON = 12.933200;
    private static final double FALKENSEE_LAT = 52.559300;
    private static final double FALKENSEE_LON = 13.097300;
    private static final double BERNAU_LAT = 52.681500;
    private static final double BERNAU_LON = 13.587200;
    private static final double KW_LAT = 52.300000;
    private static final double KW_LON = 13.630000;

    @Test
    void addressLookup_findsRealWerderAddressWithFullQuery() {
        // echte OSM-Adresse aus dem lokalen Datensatz: Aalweg 6, 14542 Werder (Havel)
        List<GeoAddress> hits = geo.searchAddress("Aalweg 6, 14542 Werder (Havel)");
        assertEquals(1, hits.size());
        assertEquals("Aalweg", hits.get(0).street());
        assertEquals("6", hits.get(0).houseNumber());
        assertEquals("14542", hits.get(0).postalCode());
        assertEquals("Potsdam-Mittelmark", hits.get(0).district());
    }

    @Test
    void addressLookup_streetQuery_returnsSortedHits() {
        List<GeoAddress> hits = geo.searchAddress("Aalweg, 14542 Werder");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(a -> "Potsdam-Mittelmark".equals(a.district())));
        // sorted by house number
        assertEquals("6", hits.get(0).houseNumber());
    }

    @Test
    void addressLookup_unknownAddress_returnsEmpty() {
        assertTrue(geo.searchAddress("Musterweg 99, 99999 Brandenburg").isEmpty());
        assertTrue(geo.searchAddress("").isEmpty());
    }

    @Test
    void pointInPolygon_determinesGebietDeterministically() {
        Verwaltungsgebiet potsdam = geo.determineGebiet(POTSDAM_LAT, POTSDAM_LON);
        assertNotNull(potsdam);
        assertEquals("Potsdam", potsdam.name());
        assertEquals("Landeshauptstadt Potsdam", potsdam.authority());

        Verwaltungsgebiet oberhavel = geo.determineGebiet(ORANIENBURG_LAT, ORANIENBURG_LON);
        assertNotNull(oberhavel);
        assertEquals("Oberhavel", oberhavel.name());

        Verwaltungsgebiet havelland = geo.determineGebiet(FALKENSEE_LAT, FALKENSEE_LON);
        assertNotNull(havelland);
        assertEquals("Havelland", havelland.name());

        Verwaltungsgebiet barnim = geo.determineGebiet(BERNAU_LAT, BERNAU_LON);
        assertNotNull(barnim);
        assertEquals("Barnim", barnim.name());

        Verwaltungsgebiet dahme = geo.determineGebiet(KW_LAT, KW_LON);
        assertNotNull(dahme);
        assertEquals("Dahme-Spreewald", dahme.name());
    }

    @Test
    void multiPolygonRings_oddParityDeterminesMembership() {
        // Brandenburg an der Havel besteht aus mehreren Polygonteilen
        // (MultiPolygon) — die Ring-Parität bestimmt die Mitgliedschaft.
        Jurisdiction j = geo.jurisdictionFor(BRB_LAT, BRB_LON);
        assertTrue(j.resolved());
        assertEquals("Brandenburg an der Havel", j.district());
    }

    @Test
    void jurisdiction_outsideAllPolygons_isNotResolved() {
        // Koordinaten außerhalb der erfassten Zuständigkeitsgebiete
        // (z. B. Hamburg) → ehrlich keine Zuständigkeit
        Jurisdiction j = geo.jurisdictionFor(53.55, 9.99);
        assertFalse(j.resolved());
        assertNull(j.district());
    }

    @Test
    void jurisdiction_knownCoordinate_returnsGebietAndAuthority() {
        Jurisdiction j = geo.jurisdictionFor(POTSDAM_LAT, POTSDAM_LON);
        assertTrue(j.resolved());
        assertEquals("Potsdam", j.district());
        assertEquals("Landeshauptstadt Potsdam", j.authority());

        Jurisdiction j2 = geo.jurisdictionFor(ORANIENBURG_LAT, ORANIENBURG_LON);
        assertEquals("Oberhavel", j2.district());
        assertEquals("Landkreis Oberhavel", j2.authority());
    }

    @Test
    void nearestAddress_findsCloseRealAddress() {
        // nahe der echten Werder-Adresse Aalweg 6 → nächste erfasste Adresse
        var near = geo.nearestAddress(WERDER_LAT, WERDER_LON, 2000);
        assertTrue(near.isPresent());
        assertTrue(GeoService.distanceMeters(WERDER_LAT, WERDER_LON,
                near.get().lat(), near.get().lon()) <= 2000,
                "nächste erfasste Adresse muss innerhalb des Radius liegen");

        // außerhalb des Radius → ehrlich leer
        assertTrue(geo.nearestAddress(53.55, 9.99, 100).isEmpty());
    }

    @Test
    void bundledAddresses_allResolveToTheirDeclaredGebiet() {
        // Selbstkonsistenz des gebündelten Datensatzes gegen die Grenzen:
        // das deklarierte Zuständigkeitsgebiet jeder Adresse muss dem
        // deterministischen Punkt-in-Polygon-Ergebnis entsprechen.
        List<GeoAddress> all = geo.addresses();
        assertFalse(all.isEmpty(), "erwartet lokale Demo-Adressen");
        for (GeoAddress a : all) {
            Jurisdiction j = geo.jurisdictionFor(a.lat(), a.lon());
            assertTrue(j.resolved(), "Adresse außerhalb aller Gebietsgrenzen: " + a.fullAddress());
            assertEquals(a.district(), j.district(),
                    "Gebietsangabe von " + a.fullAddress() + " weicht von der Geometrie ab");
        }
    }

    @Test
    void pointInPolygon_worksForExactBoundaryCoordinates() {
        // Polygon point exactly on an edge must not crash and returns a boolean.
        List<double[]> square = List.of(new double[]{13.0, 52.0}, new double[]{14.0, 52.0},
                new double[]{14.0, 53.0}, new double[]{13.0, 53.0});
        assertTrue(GeoService.pointInPolygon(52.5, 13.5, square));
        assertFalse(GeoService.pointInPolygon(54.0, 13.5, square));
    }
}
