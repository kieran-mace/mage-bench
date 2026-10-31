package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import mage.client.bridge.draft.BridgeDraftSeat;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class GetSetCardsTool {

    public static class Result {
        @ResultField(description = "Number of distinct cards")
        public Integer count;

        @ResultField(description = "Cards: name, mana_cost, types, subtypes, rarity, pt, rules")
        public List<Map<String, Object>> cards;

        @ResultField(description = "Error message")
        public String error;
    }

    @Tool(
        name = "get_set_cards",
        description = "List every card in the set being drafted (or a given set code) with its "
            + "rules text. Large: use it to plan before the draft, not between picks."
    )
    public static Result execute(
            BridgeCallbackHandler handler,
            @Param(description = "Set code (default: the draft's set)") String set_code) {
        Result result = new Result();
        BridgeDraftSeat seat = handler.draftSeat();
        if (seat == null) {
            result.error = "This bridge is not seated in a tournament.";
            return result;
        }
        result.cards = seat.setCards(set_code);
        result.count = result.cards.size();
        if (result.cards.isEmpty()) {
            result.error = "Set not known yet: the draft has not started. Pass set_code, e.g. \"FDN\".";
        }
        return result;
    }

    public static List<Map<String, Object>> examples() {
        return List.of(example("Set list", json("count", 271, "cards", List.of(
            json("name", "Llanowar Elves", "mana_cost", "{G}", "rarity", "COMMON", "rules", List.of("{T}: Add {G}."))))));
    }
}
