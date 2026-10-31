package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import mage.client.bridge.draft.BridgeDraftSeat;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class DraftPickTool {
    @Tool(
        name = "draft_pick",
        description = "Pick a card from your current pack by exact name, then block until your next "
            + "pack (or deck building) and return the new draft state. Give a short reason."
    )
    public static BridgeDraftSeat.State execute(
            BridgeCallbackHandler handler,
            @Param(description = "Exact name of a card in your current pack", required = true) String card_name,
            @Param(description = "One sentence on why you take this card") String reason) {
        BridgeDraftSeat seat = handler.draftSeat();
        if (seat == null) {
            BridgeDraftSeat.State state = new BridgeDraftSeat.State();
            state.error = "This bridge is not seated in a tournament.";
            return state;
        }
        return seat.submitPick(card_name, reason);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Next pack arrives", json(
                "phase", "drafting", "pick_pending", true, "last_pick", "Burst Lightning",
                "pack_number", 1, "pick_number", 2)));
    }
}
