package docrag.spring;


import java.nio.file.Path;
import java.time.Duration;

public class ResearchProperties {
    private String legacyDashboardRedirect = "/";
    private boolean enabled;
    private boolean initializeSchema;
    private Path directory = Path.of("data/research");
    private String ollamaUrl = "http://localhost:11434";
    private String chatModel = "qwen2.5:14b";
    private String embeddingModel = "embeddinggemma";
    private String schema = "document_research";
    private int embeddingDimensions = 768;
    private String doclingUrl = "http://localhost:5001";
    private String doclingVersion = "operator-pinned-backend";
    private String tokenizer = "google/embeddinggemma-300m";
    private int chunkTokens = 512;
    private Duration modelTimeout = Duration.ofMinutes(3);
    private int modelContextTokens = 16384;
    private int modelOutputTokens = 2048;
    private int modelCpuThreads = 4;
    private boolean modelThinking;
    private String verifierModel = "";
    private int perOwnerCapacity = 2;
    private boolean sharedHost = true;
    private Duration jobTimeout = Duration.ofMinutes(30);
    private Duration idleGrace = Duration.ofSeconds(60);
    private int queueCapacity = 8;
    private boolean manageVm;
    private long maxFileBytes = 50L * 1024 * 1024;

    public String getLegacyDashboardRedirect() {
        return legacyDashboardRedirect;
    }

    public void setLegacyDashboardRedirect(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//"))
            throw new IllegalArgumentException("Expected a local redirect path");
        legacyDashboardRedirect = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean value) {
        initializeSchema = value;
    }

    public int getModelOutputTokens() {
        return modelOutputTokens;
    }

    public void setModelOutputTokens(int value) {
        modelOutputTokens = value;
    }

    public int getModelCpuThreads() {
        return modelCpuThreads;
    }

    public void setModelCpuThreads(int value) {
        modelCpuThreads = value;
    }

    public boolean isModelThinking() {
        return modelThinking;
    }

    public void setModelThinking(boolean value) {
        modelThinking = value;
    }

    public String getVerifierModel() {
        return verifierModel;
    }

    public void setVerifierModel(String value) {
        verifierModel = value;
    }

    public int getPerOwnerCapacity() {
        return perOwnerCapacity;
    }

    public void setPerOwnerCapacity(int value) {
        perOwnerCapacity = value;
    }

    public boolean isSharedHost() {
        return sharedHost;
    }

    public void setSharedHost(boolean value) {
        sharedHost = value;
    }

    public boolean isManageVm() {
        return manageVm;
    }

    public void setManageVm(boolean value) {
        manageVm = value;
    }

    public Path getDirectory() {
        return directory;
    }

    public void setDirectory(Path value) {
        directory = value;
    }

    public String getOllamaUrl() {
        return ollamaUrl;
    }

    public void setOllamaUrl(String value) {
        ollamaUrl = value;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String value) {
        chatModel = value;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String value) {
        embeddingModel = value;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String value) {
        schema = value;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int value) {
        embeddingDimensions = value;
    }

    public String getDoclingUrl() {
        return doclingUrl;
    }

    public void setDoclingUrl(String value) {
        doclingUrl = value;
    }

    public String getDoclingVersion() {
        return doclingVersion;
    }

    public void setDoclingVersion(String value) {
        doclingVersion = value;
    }

    public String getTokenizer() {
        return tokenizer;
    }

    public void setTokenizer(String value) {
        tokenizer = value;
    }

    public int getChunkTokens() {
        return chunkTokens;
    }

    public void setChunkTokens(int value) {
        chunkTokens = value;
    }

    public Duration getModelTimeout() {
        return modelTimeout;
    }

    public void setModelTimeout(Duration value) {
        modelTimeout = value;
    }

    public int getModelContextTokens() {
        return modelContextTokens;
    }

    public void setModelContextTokens(int value) {
        modelContextTokens = value;
    }

    public Duration getJobTimeout() {
        return jobTimeout;
    }

    public void setJobTimeout(Duration value) {
        jobTimeout = value;
    }

    public Duration getIdleGrace() {
        return idleGrace;
    }

    public void setIdleGrace(Duration value) {
        idleGrace = value;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int value) {
        queueCapacity = value;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long value) {
        maxFileBytes = value;
    }
}

