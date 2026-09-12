package reasoning.ai.config;

import reasoning.ai.model.DomainKnowledge;
import reasoning.ai.model.DomainKnowledge.TermWeight;
import reasoning.ai.model.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Loads externalized domain knowledge from {@code config/domain-knowledge.yml}
 * and exposes it as a {@link DomainKnowledge} bean.
 *
 * <p>If the YAML file is missing, empty, or unparseable the application
 * fails to start with a clear error — there is no silent fallback.
 * Domain terminology lives exclusively in the YAML configuration.
 */
@Configuration
public class DomainKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(DomainKnowledgeConfig.class);
    private static final String YAML_PATH = "config/domain-knowledge.yml";

    @Bean
    public DomainKnowledge domainKnowledge() {
        ClassPathResource resource = new ClassPathResource(YAML_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "config/domain-knowledge.yml not found on classpath — required application configuration");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = yaml.load(resource.getInputStream());
            root = parsed;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse config/domain-knowledge.yml: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IllegalStateException(
                    "config/domain-knowledge.yml is empty — required application configuration");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> platform = (Map<String, Object>) root.get("platform");
        @SuppressWarnings("unchecked")
        Map<String, Object> ai = (Map<String, Object>) platform.get("ai");
        @SuppressWarnings("unchecked")
        Map<String, Object> domainConfig = (Map<String, Object>) ai.get("domain");

        // ── Algorithm parameters ──
        double compoundPenalty = toDouble(domainConfig.get("compound-embedding-penalty"), 0.85);
        double minPrimary = toDouble(domainConfig.get("minimum-primary-confidence"), 0.15);
        double minSecondary = toDouble(domainConfig.get("minimum-secondary-confidence"), 0.20);

        // ── Word boundary terms ──
        @SuppressWarnings("unchecked")
        List<String> wbList = (List<String>) domainConfig.get("word-boundary-terms");
        Set<String> wordBoundaryTerms = wbList != null
                ? Set.copyOf(wbList) : Set.of();

        // ── Domain terms ──
        @SuppressWarnings("unchecked")
        Map<String, Object> domains = (Map<String, Object>) domainConfig.get("domains");
        Map<Domain, List<TermWeight>> domainTermMap = new LinkedHashMap<>();

        for (Domain domain : Domain.allClassifiable()) {
            List<TermWeight> terms = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> domainData = (Map<String, Object>) domains.get(domain.name());
            if (domainData != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> termList = (List<Map<String, Object>>) domainData.get("terms");
                if (termList != null) {
                    for (Map<String, Object> t : termList) {
                        String phrase = (String) t.get("phrase");
                        double weight = toDouble(t.get("weight"), 5.0);
                        if (phrase != null && !phrase.isBlank()) {
                            terms.add(new TermWeight(phrase, weight));
                        }
                    }
                }
            }
            domainTermMap.put(domain, terms);
        }

        DomainKnowledge dk = new DomainKnowledge(domainTermMap, wordBoundaryTerms,
                compoundPenalty, minPrimary, minSecondary);
        dk.validate();
        log.info("Domain knowledge loaded from {}: {} domains, {} terms total",
                YAML_PATH, domainTermMap.size(),
                domainTermMap.values().stream().mapToInt(List::size).sum());
        return dk;
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
