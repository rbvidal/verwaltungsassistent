package verwaltungsassistent.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic geographic foundation for the Verwaltungsassistent.
 *
 * <p>Structured geographic knowledge (official administrative boundaries of
 * Brandenburg — Landkreise and kreisfreie Städte — plus real local demo
 * addresses from OpenStreetMap) is bundled as resources and evaluated with
 * pure geometric logic — no LLM, no external geocoding, no map tiles. The map
 * in the UI is only the visualization; this service is the source of truth.</p>
 */
@Service
public class GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EARTH_RADIUS_M = 6371000.0;

    /**
     * One Zuständigkeitsgebiet (administrative area) with its official
     * boundary rings (lon/lat pairs). Ring parity determines membership:
     * odd ring count = inside (handles MultiPolygon parts and holes uniformly).
     */
    public record Verwaltungsgebiet(String id, String name, String authority,
                                    List<List<double[]>> rings) {}

    /** One deterministic demo address from the bundled local dataset. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record GeoAddress(String street, String houseNumber, String postalCode, String city,
                             String district, double lat, double lon) {
        public String fullAddress() {
            return street + " " + houseNumber + ", " + postalCode + " " + city;
        }
    }

    /** Result of a jurisdiction determination. */
    public record Jurisdiction(String district, String authority, boolean resolved) {}

    private final List<Verwaltungsgebiet> gebiete;
    private final List<GeoAddress> addresses;
    private final List<StreetWay> streets;

    public GeoService() {
        this.gebiete = loadGebiete();
        this.addresses = loadAddresses();
        this.streets = loadStreets();
        log.info("GeoService geladen: {} Zuständigkeitsgebiete, {} Demo-Adressen, {} Straßen (OSM)",
                gebiete.size(), addresses.size(), streets.size());
    }

    private List<Verwaltungsgebiet> loadGebiete() {
        List<Verwaltungsgebiet> result = new ArrayList<>();
        try (InputStream in = new ClassPathResource("geodata/brandenburg-verwaltungsgebiete.geojson").getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            for (JsonNode f : root.path("features")) {
                String id = f.path("properties").path("id").asText();
                String name = f.path("properties").path("name").asText();
                String authority = f.path("properties").path("authority").asText();
                // Polygon: coordinates = [ring, ...]; MultiPolygon: [ [ring,...], ... ]
                JsonNode geom = f.path("geometry");
                List<List<double[]>> rings = new ArrayList<>();
                if ("MultiPolygon".equals(geom.path("type").asText())) {
                    for (JsonNode poly : geom.path("coordinates")) {
                        for (JsonNode ring : poly) {
                            rings.add(parseRing(ring));
                        }
                    }
                } else {
                    for (JsonNode ring : geom.path("coordinates")) {
                        rings.add(parseRing(ring));
                    }
                }
                result.add(new Verwaltungsgebiet(id, name, authority, rings));
            }
        } catch (Exception e) {
            log.error("Konnte Brandenburger Verwaltungsgebiete nicht laden: {}", e.getMessage());
        }
        return result;
    }

    private static List<double[]> parseRing(JsonNode ring) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode p : ring) {
            points.add(new double[]{p.get(0).asDouble(), p.get(1).asDouble()});
        }
        return points;
    }

    private List<GeoAddress> loadAddresses() {
        try (InputStream in = new ClassPathResource("geodata/brandenburg-demo-addresses.json").getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<List<GeoAddress>>() {});
        } catch (Exception e) {
            log.error("Konnte Demo-Adressdaten nicht laden: {}", e.getMessage());
            return List.of();
        }
    }

    /** Eine OSM-Straße (Liniengeometrie) mit Namen — nur als Rückgeocoding-Hilfe. */
    public record StreetWay(String name, List<double[]> points) {}

    private List<StreetWay> loadStreets() {
        List<StreetWay> result = new ArrayList<>();
        try (InputStream in = new ClassPathResource("geodata/brandenburg-streets-werder.geojson").getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            for (JsonNode f : root.path("features")) {
                String name = f.path("properties").path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                List<double[]> pts = new ArrayList<>();
                for (JsonNode c : f.path("geometry").path("coordinates")) {
                    pts.add(new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
                }
                if (pts.size() >= 2) result.add(new StreetWay(name, pts));
            }
        } catch (Exception e) {
            log.error("Konnte OSM-Straßendaten nicht laden: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Nächste erfasste OSM-Straße innerhalb des Radius (nur Namen — keine
     * Hausnummer). Abstand über die Scheitelpunkte der Liniengeometrie.
     */
    public Optional<StreetWay> nearestStreet(double lat, double lon, double maxMeters) {
        StreetWay best = null;
        double bestDistance = maxMeters;
        for (StreetWay w : streets) {
            for (double[] p : w.points()) {
                double d = distanceMeters(lat, lon, p[1], p[0]);
                if (d <= bestDistance) {
                    bestDistance = d;
                    best = w;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** All bundled Zuständigkeitsgebiete (for the map + jurisdiction). */
    public List<Verwaltungsgebiet> gebiete() {
        return gebiete;
    }

    /** All bundled demo addresses. */
    public List<GeoAddress> addresses() {
        return addresses;
    }

    /**
     * Local deterministic address lookup: normalized contains-matching on
     * street, house number and postal code. An unknown address returns an
     * empty list — the system never pretends a location was resolved.
     *
     * <p>When the query names a house number that exists in the local
     * dataset, the result is narrowed to that exact house number. A house
     * number that is not mapped (e.g. a house number absent from the local
     * dataset)
     * falls back to the street + postal code hits — honest, never invented.</p>
     */
    /** Result of an address search: hits plus whether an exact house number match exists. */
    public record SearchResult(List<GeoAddress> hits, boolean exactHouseNumber) {}

    public List<GeoAddress> searchAddress(String query) {
        return searchAddressDetailed(query).hits();
    }

    /**
     * Like {@link #searchAddress} but reports honestly whether the query was
     * matched by an exact house number ({@code exactHouseNumber=true}) or only
     * by street/postal code (the exact number is not in the local dataset).
     */
    public SearchResult searchAddressDetailed(String query) {
        if (query == null || query.isBlank()) {
            return new SearchResult(List.of(), false);
        }
        String norm = normalize(query);
        List<GeoAddress> hits = new ArrayList<>();
        for (GeoAddress a : addresses) {
            String street = normalize(a.street());
            boolean streetMatch = street.contains(norm) || norm.contains(street);
            boolean codeMatch = norm.contains(a.postalCode());
            boolean numberMatch = !a.houseNumber().isBlank() && norm.contains(normalize(a.houseNumber()));
            if (streetMatch && (codeMatch || numberMatch)) {
                hits.add(a);
            }
        }
        // exact house number in the query narrows the result to that number
        boolean exact = false;
        String queryNumber = extractNumber(norm);
        if (queryNumber != null && !isPostalCode(norm, queryNumber)) {
            List<GeoAddress> exactHits = hits.stream()
                    .filter(a -> normalize(a.houseNumber()).contains(queryNumber))
                    .toList();
            if (!exactHits.isEmpty()) {
                hits = new ArrayList<>(exactHits);
                exact = true;
            }
        }
        hits.sort(Comparator.comparing(GeoAddress::street)
                .thenComparingInt(a -> numericHouseNumber(a.houseNumber()))
                .thenComparing(GeoAddress::postalCode));
        return new SearchResult(hits, exact);
    }

    /** First digit sequence in the normalized query, or null. */
    private static String extractNumber(String norm) {
        int start = -1;
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (c >= '0' && c <= '9') {
                if (start < 0) start = i;
            } else if (start >= 0) {
                return norm.substring(start, i);
            }
        }
        return start >= 0 ? norm.substring(start) : null;
    }

    private static boolean isPostalCode(String norm, String number) {
        return number.length() == 5 && norm.contains(number);
    }

    private static int numericHouseNumber(String houseNumber) {
        int i = 0;
        while (i < houseNumber.length() && Character.isDigit(houseNumber.charAt(i))) i++;
        return i == 0 ? Integer.MAX_VALUE : Integer.parseInt(houseNumber.substring(0, i));
    }

    /**
     * Nearest known address within maxMeters (haversine). Empty when no
     * bundled address is close enough — the system never invents a location.
     */
    public Optional<GeoAddress> nearestAddress(double lat, double lon, double maxMeters) {
        GeoAddress best = null;
        double bestDistance = maxMeters;
        for (GeoAddress a : addresses) {
            double d = distanceMeters(lat, lon, a.lat(), a.lon());
            if (d <= bestDistance) {
                bestDistance = d;
                best = a;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Haversine distance in meters between two coordinates. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    /**
     * Determines the Zuständigkeitsgebiet for a coordinate deterministically
     * via point-in-polygon (ray casting) over the bundled administrative
     * boundaries. Returns the first matching Gebiet; null when no boundary
     * contains the point.
     */
    public Verwaltungsgebiet determineGebiet(double lat, double lon) {
        for (Verwaltungsgebiet g : gebiete) {
            if (pointInPolygonRings(lat, lon, g.rings())) {
                return g;
            }
        }
        return null;
    }

    /**
     * Point-in-polygon over multiple rings (MultiPolygon/holes): a point is
     * inside when it crosses an odd number of ring boundaries.
     */
    static boolean pointInPolygonRings(double lat, double lon, List<List<double[]>> rings) {
        int crossings = 0;
        for (List<double[]> ring : rings) {
            if (pointInPolygon(lat, lon, ring)) crossings++;
        }
        return crossings % 2 == 1;
    }

    /** Zuständige Stelle für eine Koordinate (Zuständigkeitsgebiet + Behörde). */
    public Jurisdiction jurisdictionFor(double lat, double lon) {
        Verwaltungsgebiet g = determineGebiet(lat, lon);
        if (g == null) {
            return new Jurisdiction(null, null, false);
        }
        return new Jurisdiction(g.name(), g.authority(), true);
    }

    /**
     * Ray-casting point-in-polygon test. Polygon points are [lon, lat] pairs;
     * the test works on (lat, lon) coordinates.
     */
    static boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double[] pi = polygon.get(i);
            double[] pj = polygon.get(j);
            boolean intersects = ((pi[1] > lat) != (pj[1] > lat))
                    && (lon < (pj[0] - pi[0]) * (lat - pi[1]) / (pj[1] - pi[1]) + pi[0]);
            if (intersects) inside = !inside;
        }
        return inside;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.GERMANY)
                .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
                .replace("straße", "strasse")
                .replaceAll("[^a-z0-9 ]", " ").trim();
    }
}
