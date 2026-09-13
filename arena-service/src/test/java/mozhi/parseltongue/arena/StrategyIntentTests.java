package mozhi.parseltongue.arena;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StrategyIntentTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String GOOD = """
            {"supported":true,"summary":"保守求生","priorities":["躲避碰撞","饥饿时寻找食物"],
             "risk":"cautious","constraints":[],"assumptions":["优先生存"]}
            """;

    @Test void acceptsStructuredBriefAndOptionalJsonFence() {
        var brief=StrategyIntent.parse(GOOD,mapper);
        assertEquals(List.of("躲避碰撞","饥饿时寻找食物"),brief.priorities());
        assertEquals(brief,StrategyIntent.parse("```json\n"+GOOD+"```",mapper));
        for(String description:List.of("稳", "凶猛一点", "围堵对手", "随便生成一条", "I prefer avoiding enemies"))
            assertDoesNotThrow(()->StrategyIntent.checkRequest(new StrategyController.Request("🐍",description)));
    }

    @Test void rejectsMalformedOrUnboundedModelOutput() {
        for(String text:List.of("not JSON", "[]", GOOD+"{}", GOOD.replace("true","\"true\""),
                GOOD.replace("cautious","whatever"), GOOD.replace("\"constraints\":[]", "\"constraints\":[12]"),
                GOOD.replace("\"supported\":true", "\"model\":\"injected\",\"supported\":true"),
                GOOD.replace("保守求生","x".repeat(601)), "x".repeat(8193),
                GOOD.replace("[\"躲避碰撞\",\"饥饿时寻找食物\"]","[]"))) {
            assertThrows(IllegalArgumentException.class,()->StrategyIntent.parse(text,mapper));
        }
    }
}
