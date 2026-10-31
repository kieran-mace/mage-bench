package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import mage.client.bridge.draft.BridgeDraftSeat;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class SubmitDeckTool {
    @Tool(
        name = "submit_deck",
        description = "Submit your Limited deck while deck building: at least 40 cards, one "
            + "'<count> <card name>' per line, using cards from your pool plus any number of basic "
            + "lands. Returns deck_result ACCEPTED or REJECTED with the reason."
    )
    public static BridgeDraftSeat.State execute(
            BridgeCallbackHandler handler,
            @Param(description = "Decklist, one '<count> <card name>' per line", required = true) String decklist,
            @Param(description = "2-4 sentences on the deck's plan") String reason) {
        BridgeDraftSeat seat = handler.draftSeat();
        if (seat == null) {
            BridgeDraftSeat.State state = new BridgeDraftSeat.State();
            state.error = "This bridge is not seated in a tournament.";
            return state;
        }
        return seat.submitDeckList(decklist, reason);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Accepted", json("phase", "deck_submitted", "deck_result", "ACCEPTED")),
            example("Rejected", json("phase", "building",
                "deck_result", "REJECTED: deck has 38 cards; Limited decks need at least 40")));
    }
}
