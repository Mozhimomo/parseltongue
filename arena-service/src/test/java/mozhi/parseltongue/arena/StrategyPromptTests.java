package mozhi.parseltongue.arena;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StrategyPromptTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StrategyIntent brief = new StrategyIntent(true,"优先生存",List.of("避碰","找食物"),"cautious",List.of(),List.of());

    @Test void longRepairKeepsContractAndBriefWithinGatewayBudget() {
        String user = StrategyPrompt.userContent("蛇", "谨慎".repeat(4000), brief, mapper);
        var messages = new ArrayList<>(List.of(Map.of("role","system","content",StrategyPrompt.GENERATION),
                Map.of("role","user","content",user)));
        var original = List.copyOf(messages);
        for (int i=0;i<2;i++) {
            StrategyPrompt.addCorrection(messages,"# long source\n".repeat(4000),"TIMEOUT");
            assertEquals(original,messages.subList(0,2));
            assertEquals(4,messages.size());
            assertTrue(messages.stream().mapToInt(m->m.get("content").length()).sum()<=32000);
            assertTrue(messages.get(2).get("content").length()<16000);
        }
        assertFalse(mapper.readTree(user).has("description_truncated"));
    }

    @Test void escapedLongInputRetainsFullIntentAndMarksOnlyOriginalExcerpt() {
        // Maximum JSON escaping plus a large brief previously overflowed 32k.
        var longBrief=new StrategyIntent(true,"x".repeat(600),List.of("a".repeat(240),"b".repeat(240)),
                "balanced",java.util.Collections.nCopies(8,"c".repeat(240)),java.util.Collections.nCopies(8,"d".repeat(240)));
        String original="a\"\\".repeat(2666);
        String text=StrategyPrompt.userContent("蛇",original,longBrief,mapper);
        var node=mapper.readTree(text);
        assertTrue(node.path("description_truncated").asBoolean());
        assertTrue(original.startsWith(node.path("description").asText()));
        assertEquals(mapper.valueToTree(longBrief),node.path("intent"));
        assertTrue(text.length()+StrategyPrompt.GENERATION.length()+1024<=32000);
    }
}
