package mage.client.bridge.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mage.client.bridge.BridgeCallbackHandler;
import static mage.client.bridge.tools.McpToolRegistry.example;
import static mage.client.bridge.tools.McpToolRegistry.json;

/**
 * Searches the Magic Comprehensive Rules text (a local copy whose path is given by
 * -Dxmage.bridge.rulesFile, e.g. MagicCompRules.txt from magic.wizards.com/en/rules).
 */
public class LookupRulesTool {

    private static volatile List<String> rules;

    public static class Result {
        @ResultField(description = "Matching rules (number and text), best matches first")
        public List<String> matches;

        @ResultField(description = "Total number of matches before truncation")
        public Integer total_matches;

        @ResultField(description = "Error message")
        public String error;
    }

    @Tool(
        name = "lookup_rules",
        description = "Search the official Magic Comprehensive Rules. Pass a rule number (e.g. \"702.19\" "
            + "for trample) or keywords (e.g. \"deathtouch trample damage\"). Returns matching rule text."
    )
    public static Result execute(
            BridgeCallbackHandler handler,
            @Param(description = "Rule number prefix or keywords", required = true) String query,
            @Param(description = "Maximum matches to return (default 8)") Integer limit) {
        Result result = new Result();
        List<String> all = load();
        if (all == null) {
            result.error = "Rules text not available: start the bridge with -Dxmage.bridge.rulesFile=<MagicCompRules.txt>.";
            return result;
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        if (q.matches("\\d{3}(\\.\\d+[a-z]?)?\\.?")) {
            String prefix = q.endsWith(".") ? q : q + (q.length() == 3 ? "." : "");
            for (String line : all) {
                if (line.startsWith(prefix) || line.startsWith(q + " ")) {
                    matches.add(line);
                }
            }
        } else {
            String[] terms = q.split("\\s+");
            List<int[]> scored = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                String lower = all.get(i).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String term : terms) {
                    if (!term.isEmpty() && lower.contains(term)) {
                        score++;
                    }
                }
                if (score == terms.length) {
                    scored.add(new int[]{i, all.get(i).length()});
                }
            }
            // Prefer shorter (more specific) rules.
            scored.sort((a, b) -> Integer.compare(a[1], b[1]));
            for (int[] s : scored) {
                matches.add(all.get(s[0]));
            }
        }
        int max = limit == null ? 8 : Math.max(1, limit);
        result.total_matches = matches.size();
        result.matches = matches.subList(0, Math.min(max, matches.size()));
        return result;
    }

    private static List<String> load() {
        if (rules != null) {
            return rules;
        }
        String path = System.getProperty("xmage.bridge.rulesFile");
        if (path == null || !Files.exists(Path.of(path))) {
            return null;
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(path), StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
            rules = lines;
            return rules;
        } catch (IOException e) {
            return null;
        }
    }

    public static List<Map<String, Object>> examples() {
        return List.of(example("Rule lookup", json("total_matches", 1, "matches", List.of(
            "702.2b A creature with toughness greater than 0 that's been dealt damage by a source with "
                + "deathtouch since the last time state-based actions were checked is destroyed..."))));
    }
}
