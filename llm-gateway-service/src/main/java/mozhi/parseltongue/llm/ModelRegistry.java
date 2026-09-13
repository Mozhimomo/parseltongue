package mozhi.parseltongue.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ModelRegistry {
    private final Map<String, ChatModel> providers;
    private final LlmProperties properties;

    public ModelRegistry(LlmProperties properties) {
        this.properties = properties;
        for (var route : properties.models().values()) {
            if (!properties.providers().containsKey(route.provider())) {
                throw new IllegalStateException("模型注册引用了不存在的提供商: " + route.provider());
            }
        }
        Map<String, ChatModel> clients = new HashMap<>();
        properties.providers().forEach((name, provider) -> clients.put(name, OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(provider.baseUrl().toString()).apiKey(provider.apiKey())
                        .timeout(properties.timeout()).maxRetries(0)
                        .temperature(null).toolCallbacks(List.of()).build())
                .httpClientBuilderCustomizer(builder -> builder
                        .timeout(com.openai.core.Timeout.builder().request(properties.timeout())
                                .connect(Duration.ofSeconds(5)).read(properties.timeout())
                                .write(properties.timeout()).build()))
                .build()));
        this.providers = Map.copyOf(clients);
    }

    public ChatModel provider(String name) { return providers.get(name); }

    public List<ModelDescriptor> models() {
        return properties.models().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new ModelDescriptor(e.getKey(), e.getValue().provider(),
                        e.getValue().providerModel(), e.getValue().maxOutputTokens())).toList();
    }

    public record ModelDescriptor(String model, String provider, String providerModel, int maxOutputTokens) {}
}
