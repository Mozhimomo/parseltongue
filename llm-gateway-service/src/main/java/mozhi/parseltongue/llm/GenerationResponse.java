package mozhi.parseltongue.llm;

public record GenerationResponse(String llmRequestId, String taskId, String model,
                                 String provider, String providerModel, String content, String finishReason,
                                 TokenUsage usage) {
    public record TokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {}
}
