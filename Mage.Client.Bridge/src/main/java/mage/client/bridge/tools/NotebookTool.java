package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class NotebookTool {

    public static class Result {
        @ResultField(description = "The most recent notebook entries (oldest first)")
        public List<Map<String, Object>> entries;
    }

    @Tool(
        name = "notebook",
        description = "Your private notebook, kept for the whole session (draft and every game). "
            + "Write plans and thinking with text (e.g. kind=\"plan\" before the draft, kind=\"turn_plan\" "
            + "at the start of your turn, kind=\"reflection\" after a game); call with no text to read "
            + "your recent entries. Entries are recorded for later review."
    )
    public static Result execute(
            BridgeCallbackHandler handler,
            @Param(description = "Text to add; omit to just read") String text,
            @Param(description = "Entry kind, e.g. plan, turn_plan, opponent_read, reflection") String kind,
            @Param(description = "How many recent entries to return (default 10)") Integer limit) {
        Result result = new Result();
        if (text != null && !text.isBlank()) {
            handler.notebook().add(kind, text, handler.currentContextLabel());
        }
        result.entries = handler.notebook().recent(limit == null ? 10 : Math.max(1, limit));
        return result;
    }

    public static List<Map<String, Object>> examples() {
        return List.of(example("Write a turn plan", json("entries", List.of(json(
            "ts", "2026-10-31T02:10:00Z", "kind", "turn_plan", "context", "T5 Precombat Main",
            "text", "Opponent has 2 untapped Islands: play around a counter, cast the 2-drop first.")))));
    }
}
