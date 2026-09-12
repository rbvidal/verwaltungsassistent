package reasoning.ai.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A versioned, identifiable prompt template.
 * Every prompt used in the platform is registered here for reproducibility and auditing.
 *
 * <p>Prompts are categorized by their purpose ({@link Category}) and carry metadata
 * about expected output, supported models, temperature, and usage examples.
 * The {@code render()} method substitutes {@code {{variable}}} placeholders with values.
 */
public class PromptTemplate {

    /** Functional category of a prompt template. */
    public enum Category {
        RETRIEVAL, SUMMARIZATION, EXTRACTION, CLASSIFICATION,
        EVALUATION, REASONING, WORKFLOW, GRAPH, SEARCH, SYSTEM
    }

    private String id;
    private int version;
    private Category category;
    private String description;
    private String template;
    private List<String> variables;
    private String expectedOutputType;
    private List<String> supportedModels;
    private double recommendedTemperature;
    private List<Example> examples;
    private Map<String, String> metadata;

    public PromptTemplate() {}

    public PromptTemplate(String id, int version, Category category, String description,
                          String template, List<String> variables, String expectedOutputType,
                          List<String> supportedModels, double recommendedTemperature,
                          List<Example> examples, Map<String, String> metadata) {
        this.id = id;
        this.version = version;
        this.category = category;
        this.description = description;
        this.template = template;
        this.variables = variables != null ? List.copyOf(variables) : List.of();
        this.expectedOutputType = expectedOutputType;
        this.supportedModels = supportedModels != null ? List.copyOf(supportedModels) : List.of();
        this.recommendedTemperature = recommendedTemperature;
        this.examples = examples != null ? List.copyOf(examples) : List.of();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public List<String> getVariables() { return variables; }
    public void setVariables(List<String> variables) { this.variables = variables; }
    public String getExpectedOutputType() { return expectedOutputType; }
    public void setExpectedOutputType(String expectedOutputType) { this.expectedOutputType = expectedOutputType; }
    public List<String> getSupportedModels() { return supportedModels; }
    public void setSupportedModels(List<String> supportedModels) { this.supportedModels = supportedModels; }
    public double getRecommendedTemperature() { return recommendedTemperature; }
    public void setRecommendedTemperature(double recommendedTemperature) { this.recommendedTemperature = recommendedTemperature; }
    public List<Example> getExamples() { return examples; }
    public void setExamples(List<Example> examples) { this.examples = examples; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    /** Returns the fully qualified prompt identifier: "id/v{version}". */
    public String getQualifiedId() { return id + "/v" + version; }

    /** Renders the template by substituting {{variable}} placeholders. */
    public String render(Map<String, String> values) {
        String result = template;
        for (var entry : values.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /** An example input/output pair for the prompt template. */
    public record Example(Map<String, String> input, String expectedOutput) {
        public Example {
            input = Map.copyOf(input);
        }
    }
}
