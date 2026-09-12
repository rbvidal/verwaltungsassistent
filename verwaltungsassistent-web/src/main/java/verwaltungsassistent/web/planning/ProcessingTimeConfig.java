package verwaltungsassistent.web.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Initiale Referenz-Bearbeitungszeiten je Fallart (config/processing-times.yml)
 * — Konfiguration und Fallback, KEIN gelernter Zustand. Die Datei wird nur
 * gelesen und niemals umgeschrieben. Gelernte empirische Statistiken
 * ({@link EffortLearningService}) ersetzen die Baseline erst nach genügend
 * Beobachtungen; die YAML-Werte bleiben als Referenz erhalten.
 */
@Component
public class ProcessingTimeConfig {

    private static final Logger log = LoggerFactory.getLogger(ProcessingTimeConfig.class);
    private static final String FALLBACK_CATEGORY = "allgemein";
    private static final int DEFAULT_FALLBACK_MINUTES = 30;

    private final Map<String, Integer> baselines = new LinkedHashMap<>();
    private int learningInterval = 5;

    public ProcessingTimeConfig() {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            var sources = loader.load("processing-times",
                    new ClassPathResource("config/processing-times.yml"));
            if (!sources.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> flat = (Map<String, Object>) sources.get(0).getSource();
                for (Map.Entry<String, Object> e : flat.entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith("processing-times.baselines.")) {
                        baselines.put(key.substring("processing-times.baselines.".length()),
                                toInt(e.getValue()));
                    } else if (key.equals("processing-times.learning-interval")) {
                        learningInterval = toInt(e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("processing-times.yml nicht ladbar — nur allgemein/30 aktiv: {}", e.getMessage());
            baselines.put(FALLBACK_CATEGORY, DEFAULT_FALLBACK_MINUTES);
        }
        // System-Property-Override (z. B. -Dprocessing-times.baselines.wohngeld=5):
        // test-profileigene, beschleunigte Zeiten OHNE die Produktions-YAML zu
        // verändern. Produktionsläufe ohne diese Properties bleiben unverändert.
        for (String category : baselines.keySet().stream().toList()) {
            String value = System.getProperty("processing-times.baselines." + category);
            if (value != null && !value.isBlank()) {
                try {
                    baselines.put(category, Integer.parseInt(value.trim()));
                } catch (NumberFormatException ignored) {
                    log.warn("processing-times.baselines.{} ist kein Integer: '{}'", category, value);
                }
            }
        }
        String interval = System.getProperty("processing-times.learning-interval");
        if (interval != null && !interval.isBlank()) {
            try {
                learningInterval = Integer.parseInt(interval.trim());
            } catch (NumberFormatException ignored) {
                log.warn("processing-times.learning-interval ist kein Integer: '{}'", interval);
            }
        }
    }

    /** Referenzminuten einer Fallart; unbekannte Kategorien fallen auf "allgemein" zurück. */
    public int baselineMinutes(String category) {
        Integer value = baselines.get(normalize(category));
        if (value == null) {
            value = baselines.get(FALLBACK_CATEGORY);
        }
        return value != null ? value : DEFAULT_FALLBACK_MINUTES;
    }

    /** Anzahl abgeschlossener Beobachtungen, nach der die Statistik neu berechnet wird. */
    public int learningInterval() {
        return learningInterval;
    }

    /** Nur für Diagnose/Tests. */
    public Map<String, Integer> baselines() {
        return Map.copyOf(baselines);
    }

    /** YAML-Skalare kommen je nach Loader als Number oder String an. */
    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value).trim());
    }

    private static String normalize(String category) {
        return category != null ? category.trim().toLowerCase(Locale.GERMANY) : "";
    }
}
