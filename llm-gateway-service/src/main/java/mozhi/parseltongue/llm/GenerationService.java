package mozhi.parseltongue.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.SocketTimeoutException;
import java.io.InterruptedIOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class GenerationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private final ModelRegistry registry;
    private final LlmProperties properties;
    private final Semaphore slots;

    public GenerationService(ModelRegistry registry, LlmProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.slots = new Semaphore(properties.maxConcurrentCalls());
    }

    public GenerationResponse generate(GenerationRequest request, String requestId) {
        var route = properties.models().get(request.model());
        if (route == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知模型别名");
        int outputTokens = request.maxOutputTokens() == null ? route.maxOutputTokens() : request.maxOutputTokens();
        if (outputTokens > route.maxOutputTokens()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "输出 token 超出模型额度");
        }
        if (request.messages().stream().mapToLong(m -> m.content().length()).sum() > properties.maxInputCharacters()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "消息总长度超出限制");
        }
        if (request.messages().stream().noneMatch(m -> "user".equals(m.role()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少需要一条 user 消息");
        }
        if (!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "模型调用繁忙，请稍后再试");
        long started = System.nanoTime();
        String outcome = "failed";
        GenerationResponse.TokenUsage usage = null;
        try {
            var options = OpenAiChatOptions.builder().model(route.providerModel())
                    .temperature(null).toolCallbacks(List.of());
            if (route.thinking() != null && !route.thinking().isBlank()) {
                options.extraBody(Map.of("thinking", Map.of("type", route.thinking())));
            }
            if (route.completionTokenLimit()) options.maxCompletionTokens(outputTokens);
            else options.maxTokens(outputTokens);
            ChatResponse result = registry.provider(route.provider()).call(new Prompt(request.messages().stream()
                    .map(GenerationService::message).toList(), options.build()));
            if (result == null || result.getResult() == null || result.getResult().getOutput() == null
                    || result.getResult().getOutput().hasToolCalls()
                    || result.getResult().getOutput().getText() == null
                    || result.getResult().getOutput().getText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型未返回有效文本");
            }
            var rawUsage = result.getMetadata().getUsage();
            usage = rawUsage == null || rawUsage instanceof EmptyUsage ? null : new GenerationResponse.TokenUsage(
                    rawUsage.getPromptTokens(), rawUsage.getCompletionTokens(), rawUsage.getTotalTokens());
            outcome = "succeeded";
            String finishReason = result.getResult().getMetadata().getFinishReason();
            String actualModel = result.getMetadata().getModel();
            return new GenerationResponse(requestId, request.taskId(), request.model(), route.provider(),
                    actualModel == null || actualModel.isBlank() ? route.providerModel() : actualModel,
                    result.getResult().getOutput().getText(),
                    finishReason == null ? null : finishReason.toLowerCase(Locale.ROOT), usage);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) {
                outcome = "timeout_unknown";
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "模型调用超时，提供商处理状态未知");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型提供商调用失败");
        } finally {
            slots.release();
            // No prompts, generated code, API keys or upstream exception bodies in ordinary logs.
            log.info("llm requestId={} taskId={} model={} provider={} providerModel={} status={} durationMs={} usage={}",
                    requestId, request.taskId(), request.model(), route.provider(), route.providerModel(), outcome,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), usage);
        }
    }

    private static Message message(GenerationRequest.ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            case "user" -> new UserMessage(message.content());
            default -> throw new IllegalArgumentException("Unsupported role");
        };
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException
                    || cause instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }
}
