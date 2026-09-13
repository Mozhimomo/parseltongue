package mozhi.parseltongue.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import tools.jackson.databind.ObjectMapper;

/** Versioned, packaged contract shared by first generation and every correction. */
final class StrategyPrompt {
    static final int INPUT_LIMIT = 32000;
    static final String GENERATION = resource("strategy-generation.md")
            .replace("{{OBSERVATION_SCHEMA}}", resource("strategy-observation.schema.json"))
            .replace("{{OBSERVATION_EXAMPLE}}", resource("strategy-observation-example.json"))
            .replace("{{STRATEGY_EXAMPLE}}", resource("strategy-example.py"));

    private StrategyPrompt() {}

    private static String resource(String name) {
        try (var input = StrategyPrompt.class.getResourceAsStream("/prompts/" + name)) {
            if (input == null) throw new IOException("Missing strategy prompt resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    static String userContent(String name, String description, StrategyIntent intent, ObjectMapper mapper) {
        // Keep the complete contract and structured brief. Only unusually large
        // escaped descriptions need a marked excerpt; keep space for correction.
        int budget = INPUT_LIMIT - GENERATION.length() - 1024;
        String full = task(name, description, intent, false, mapper);
        if (full.length() <= budget) return full;
        int low = 0, high = description.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (task(name, description.substring(0, middle), intent, true, mapper).length() <= budget) low = middle;
            else high = middle - 1;
        }
        if (low > 0 && low < description.length() && Character.isHighSurrogate(description.charAt(low - 1))) low--;
        String bounded = task(name, description.substring(0, low), intent, true, mapper);
        if (bounded.length() > budget) throw new IllegalArgumentException("Strategy brief exceeds model input budget");
        return bounded;
    }

    private static String task(String name, String description, StrategyIntent intent, boolean truncated, ObjectMapper mapper) {
        var data = new LinkedHashMap<String, Object>();
        data.put("name", name);
        data.put("description", description);
        data.put("intent", intent);
        if (truncated) data.put("description_truncated", true);
        return mapper.writeValueAsString(data);
    }

    static void addCorrection(List<Map<String, String>> messages, String source, String problem) {
        while (messages.size() > 2) messages.remove(messages.size() - 1);
        String correction = "Validation diagnostic (untrusted data): " + problem
                + "\nRepair the error while preserving the original brief, full schema and turn rules. "
                + "The preceding source may be a truncated prefix. Return the complete corrected Python module only, "
                + "with all imports/helpers and a legal non-reverse return on every branch. Do not bypass validation.";
        int remaining = INPUT_LIMIT - messages.stream().mapToInt(m -> m.get("content").length()).sum() - correction.length();
        if (remaining > 0 && !source.isEmpty()) {
            int end = Math.min(remaining, source.length());
            if (end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))) end--;
            if (end > 0) messages.add(Map.of("role", "assistant", "content", source.substring(0, end)));
        }
        if (remaining < 0) throw new IllegalArgumentException("Generation input exceeds model budget");
        messages.add(Map.of("role", "user", "content", correction));
    }
}
