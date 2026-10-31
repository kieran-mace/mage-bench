package mage.client.bridge.draft;

import com.google.gson.Gson;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An agent's notebook: free-text plans and thoughts written through the
 * `notebook` tool. It lives on the client, so it persists across the draft and
 * every game of a bridge session, and each entry is logged (when a log path is
 * set) so the agent's reasoning can be inspected afterwards.
 */
public class BridgeNotebook {

    private static final Gson GSON = new Gson();

    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final PrintWriter log;

    public BridgeNotebook(String logPath) throws IOException {
        this.log = logPath == null ? null : new PrintWriter(new FileWriter(logPath, true), true);
    }

    public synchronized Map<String, Object> add(String kind, String text, String context) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("kind", kind == null || kind.isBlank() ? "note" : kind);
        if (context != null) {
            entry.put("context", context);
        }
        entry.put("text", text);
        entries.add(entry);
        if (log != null) {
            log.println(GSON.toJson(entry));
        }
        return entry;
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        return new ArrayList<>(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
    }
}
