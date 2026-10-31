package mage.client.bridge.tools;

import java.util.List;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import mage.client.bridge.draft.BridgeDraftSeat;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

public class GetDraftStateTool {
    @Tool(
        name = "get_draft_state",
        description = "Draft/deck-building state for a tournament seat. With wait=true (default) blocks "
            + "until you have a pick to make or a deck to build (up to ~2 minutes), then returns the "
            + "current pack, your picks, and during building your pool."
    )
    public static BridgeDraftSeat.State execute(
            BridgeCallbackHandler handler,
            @Param(description = "Block until a decision is pending (default true)") Boolean wait) {
        BridgeDraftSeat seat = handler.draftSeat();
        if (seat == null) {
            BridgeDraftSeat.State state = new BridgeDraftSeat.State();
            state.error = "This bridge is not seated in a tournament.";
            return state;
        }
        return Boolean.FALSE.equals(wait) ? seat.state() : seat.awaitDecision(120);
    }

    public static List<Map<String, Object>> examples() {
        return List.of(
            example("Pick pending", json(
                "phase", "drafting", "pick_pending", true, "pack_number", 1, "pick_number", 3,
                "pack", List.of(json("name", "Llanowar Elves", "mana_cost", "{G}", "rarity", "COMMON",
                    "pt", "1/1", "rules", List.of("{T}: Add {G}."))),
                "picks", List.of("Burst Lightning", "Shivan Dragon"))));
    }
}
