package reasoning.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration properties for AI chat and embedding providers. */
@ConfigurationProperties(prefix = "platform.ai")
public class AiProviderProperties {

    private Ollama ollama = new Ollama();
    private OpenAi openai = new OpenAi();

    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }
    public OpenAi getOpenai() { return openai; }
    public void setOpenai(OpenAi openai) { this.openai = openai; }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String chatModel = "qwen2.5:14b";
        private String verifierModel = "qwen2.5:14b";
        private String verificationStrategy = "pairwise";
        private String embeddingModel = "nomic-embed-text";
        private int embeddingDimension = 768;
        private String requestTimeout = "120s";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public String getVerifierModel() { return verifierModel; }
        public void setVerifierModel(String verifierModel) { this.verifierModel = verifierModel; }
        public String getVerificationStrategy() { return verificationStrategy; }
        public void setVerificationStrategy(String verificationStrategy) { this.verificationStrategy = verificationStrategy; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
        public String getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(String requestTimeout) { this.requestTimeout = requestTimeout; }
    }

    public static class OpenAi {
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String chatModel = "gpt-4o";
        private String embeddingModel = "text-embedding-3-small";
        private int embeddingDimension = 1536;
        private String requestTimeout = "60s";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
        public String getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(String requestTimeout) { this.requestTimeout = requestTimeout; }
    }
}
