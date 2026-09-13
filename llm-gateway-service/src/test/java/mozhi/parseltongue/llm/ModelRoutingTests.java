package mozhi.parseltongue.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelRoutingTests {
    @Test void tiersCanIndependentlySelectProvidersModelsAndTokenConventions() {
        var properties = new LlmProperties("x".repeat(32), Duration.ofSeconds(2), 2, 1000,
                Map.of("budget", new LlmProperties.Provider("budget-key", URI.create("http://localhost:1")),
                        "premium", new LlmProperties.Provider("premium-key", URI.create("http://localhost:2"))),
                Map.of("low-cost", new LlmProperties.ModelRoute("budget", "cheap-model", 100, false, "disabled"),
                        "high-capability", new LlmProperties.ModelRoute("premium", "advanced-model", 1000, true, "")));
        var registry = mock(ModelRegistry.class);
        var cheap = mock(ChatModel.class);
        var advanced = mock(ChatModel.class);
        when(registry.provider("budget")).thenReturn(cheap);
        when(registry.provider("premium")).thenReturn(advanced);
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));
        when(cheap.call(any(Prompt.class))).thenReturn(response);
        when(advanced.call(any(Prompt.class))).thenReturn(response);
        var service = new GenerationService(registry, properties);
        var messages = List.of(new GenerationRequest.ChatMessage("user", "hello"));
        assertEquals("cheap-model", service.generate(new GenerationRequest("t1", "low-cost", messages, null), "r1").providerModel());
        assertEquals("advanced-model", service.generate(new GenerationRequest("t2", "high-capability", messages, null), "r2").providerModel());
        var lowPrompt = ArgumentCaptor.forClass(Prompt.class);
        var highPrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(cheap, times(1)).call(lowPrompt.capture());
        verify(advanced, times(1)).call(highPrompt.capture());
        var low = (OpenAiChatOptions) lowPrompt.getValue().getOptions();
        var high = (OpenAiChatOptions) highPrompt.getValue().getOptions();
        assertEquals("cheap-model", low.getModel());
        assertEquals(100, low.getMaxTokens());
        assertNull(low.getMaxCompletionTokens());
        assertEquals("advanced-model", high.getModel());
        assertEquals(1000, high.getMaxCompletionTokens());
        assertNull(high.getMaxTokens());
        assertTrue(high.getExtraBody() == null || high.getExtraBody().isEmpty());
    }
}
