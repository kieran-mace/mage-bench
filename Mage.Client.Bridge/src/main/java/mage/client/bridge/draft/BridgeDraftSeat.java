package mage.client.bridge.draft;

import com.google.gson.Gson;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.client.bridge.BridgeMageClient;
import mage.client.bridge.tools.ResultField;
import mage.constants.CardType;
import mage.constants.Rarity;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.remote.Session;
import mage.view.DraftClientMessage;
import mage.view.DraftPickView;
import mage.view.DraftView;
import mage.view.SimpleCardView;
import mage.view.TableClientMessage;
import mage.view.TournamentPlayerView;
import mage.view.TournamentView;
import org.apache.log4j.Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tournament seat for a bridge client (mtgBench): handles the booster draft,
 * deck construction and between-game deck resubmission, and exposes those
 * decisions to the agent through the draft MCP tools. Game play itself stays
 * with the normal bridge game handler.
 *
 * Only active when the bridge was started with a tournament table to join.
 */
public class BridgeDraftSeat {

    private static final Logger logger = Logger.getLogger(BridgeDraftSeat.class);
    private static final Gson GSON = new Gson();
    private static final Set<String> BASICS = Set.of("Plains", "Island", "Swamp", "Mountain", "Forest");
    private static final EnumSet<ClientCallbackMethod> HANDLED = EnumSet.of(
            ClientCallbackMethod.START_TOURNAMENT, ClientCallbackMethod.START_DRAFT,
            ClientCallbackMethod.DRAFT_INIT, ClientCallbackMethod.DRAFT_PICK,
            ClientCallbackMethod.DRAFT_UPDATE, ClientCallbackMethod.DRAFT_OVER,
            ClientCallbackMethod.CONSTRUCT, ClientCallbackMethod.SIDEBOARD,
            ClientCallbackMethod.TOURNAMENT_INIT, ClientCallbackMethod.TOURNAMENT_UPDATE,
            ClientCallbackMethod.TOURNAMENT_OVER);

    /** Result of the draft tools: the seat's current draft/build state. */
    public static class State {
        @ResultField(description = "drafting, building, deck_submitted, playing, tournament_over (or waiting)")
        public String phase;
        @ResultField(description = "True when a pack is waiting for your pick")
        public Boolean pick_pending;
        @ResultField(description = "Set codes of the packs in this draft")
        public List<String> set_codes;
        @ResultField(description = "Pack number (1-3)")
        public Integer pack_number;
        @ResultField(description = "Pick number within the pack")
        public Integer pick_number;
        @ResultField(description = "Seconds until the server picks for you (absent = no timer)")
        public Integer seconds_left_to_pick;
        @ResultField(description = "Cards in the pack you are picking from")
        public List<Map<String, Object>> pack;
        @ResultField(description = "Names of the cards you have picked so far, in order")
        public List<String> picks;
        @ResultField(description = "Your full card pool (during deck building)")
        public List<Map<String, Object>> pool;
        @ResultField(description = "How to submit a deck")
        public String deck_rules;
        @ResultField(description = "The card your last pick took")
        public String last_pick;
        @ResultField(description = "ACCEPTED or REJECTED: <why>")
        public String deck_result;
        @ResultField(description = "Final standings when the tournament is over")
        public List<Map<String, Object>> standings;
        @ResultField(description = "Error message")
        public String error;
    }

    private final BridgeMageClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bridge-draft-seat");
        t.setDaemon(true);
        return t;
    });
    private final int agentPickTimeoutSec = Integer.getInteger("xmage.bridge.draftPickTimeoutSec", 0);
    private final String deckOutPath = System.getProperty("xmage.bridge.deckOut");
    private final PrintWriter log;

    // Seat state, guarded by "this".
    private String phase = "waiting";
    private List<String> setCodes = new ArrayList<>();
    private int packNumber;
    private int pickNumber;
    private Instant pickDeadline;
    private final Map<UUID, CardInfo> currentPack = new LinkedHashMap<>();
    private final List<CardInfo> picks = new ArrayList<>();
    private final List<CardInfo> pool = new ArrayList<>();
    private DeckCardLists submittedDeck;
    private String lastDeckResult;
    private UUID tournamentId;
    private List<Map<String, Object>> finalStandings;

    private final SynchronousQueue<String[]> pickAnswers = new SynchronousQueue<>();
    private final SynchronousQueue<String[]> deckAnswers = new SynchronousQueue<>();

    public BridgeDraftSeat(BridgeMageClient client, String logPath) throws IOException {
        this.client = client;
        this.log = logPath == null ? null : new PrintWriter(new FileWriter(logPath, true), true);
    }

    public boolean handles(ClientCallbackMethod method) {
        return HANDLED.contains(method);
    }

    public void onCallback(ClientCallback callback) {
        executor.submit(() -> {
            try {
                callback.decompressData();
                handle(callback);
            } catch (Exception e) {
                logger.error("[" + client.getUsername() + "] draft seat callback " + callback.getMethod() + " failed", e);
                logEvent("callback_error", Map.of("method", callback.getMethod().name(), "error", String.valueOf(e)));
            }
        });
    }

    private Session session() {
        return client.getSession();
    }

    private void handle(ClientCallback callback) throws InterruptedException {
        switch (callback.getMethod()) {
            case START_TOURNAMENT:
                synchronized (this) {
                    tournamentId = callback.getObjectId();
                }
                session().joinTournament(callback.getObjectId());
                startStandingsWatcher();
                logEvent("start_tournament", Map.of("tournamentId", callback.getObjectId().toString()));
                break;
            case START_DRAFT:
                synchronized (this) {
                    phase = "drafting";
                }
                session().joinDraft(callback.getObjectId());
                break;
            case DRAFT_UPDATE: {
                DraftView view = ((DraftClientMessage) callback.getData()).getDraftView();
                synchronized (this) {
                    packNumber = view.getBoosterNum();
                    pickNumber = view.getCardNum();
                    setCodes = new ArrayList<>(view.getSetCodes());
                }
                break;
            }
            case DRAFT_INIT:
            case DRAFT_PICK: {
                DraftClientMessage message = (DraftClientMessage) callback.getData();
                if (message.getDraftView() != null) {
                    synchronized (this) {
                        setCodes = new ArrayList<>(message.getDraftView().getSetCodes());
                    }
                }
                DraftPickView pickView = message.getDraftPickView();
                if (pickView != null && pickView.isPicking() && !pickView.getBooster().isEmpty()) {
                    session().setBoosterLoaded(callback.getObjectId());
                    pick(callback.getObjectId(), pickView);
                }
                break;
            }
            case DRAFT_OVER:
                synchronized (this) {
                    phase = "draft_over";
                    currentPack.clear();
                }
                logEvent("draft_over", Map.of("picks", names(picks)));
                break;
            case CONSTRUCT:
                construct((TableClientMessage) callback.getData());
                break;
            case SIDEBOARD: {
                // Between games of a match: keep the submitted deck.
                DeckCardLists deck;
                synchronized (this) {
                    deck = submittedDeck;
                }
                session().submitDeck(((TableClientMessage) callback.getData()).getCurrentTableId(), deck);
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ picks

    private void pick(UUID draftId, DraftPickView pickView) throws InterruptedException {
        Map<UUID, CardInfo> pack = new LinkedHashMap<>();
        for (SimpleCardView card : pickView.getBooster().values()) {
            pack.put(card.getId(), CardRepository.instance.findCard(card.getExpansionSetCode(), card.getCardNumber()));
        }
        // The server auto-picks when its timer expires, so answer a few seconds early.
        int serverTimeoutSec = pickView.getTimeout();
        int budgetSec = serverTimeoutSec > 0 ? Math.max(1, serverTimeoutSec - 4) : 0;
        if (agentPickTimeoutSec > 0) {
            budgetSec = budgetSec > 0 ? Math.min(budgetSec, agentPickTimeoutSec) : agentPickTimeoutSec;
        }
        synchronized (this) {
            phase = "drafting";
            currentPack.clear();
            currentPack.putAll(pack);
            pickAnswers.clear();
            pickDeadline = budgetSec > 0 ? Instant.now().plusSeconds(budgetSec) : null;
        }

        UUID chosen = null;
        String source = "auto";
        String reason = "";
        long deadline = budgetSec > 0 ? System.currentTimeMillis() + budgetSec * 1000L : Long.MAX_VALUE;
        while (chosen == null && System.currentTimeMillis() < deadline) {
            String[] answer = pickAnswers.poll(Math.min(deadline - System.currentTimeMillis(), 60_000), TimeUnit.MILLISECONDS);
            if (answer == null) {
                continue;
            }
            for (Map.Entry<UUID, CardInfo> entry : pack.entrySet()) {
                if (entry.getValue().getName().equalsIgnoreCase(answer[0].trim())) {
                    chosen = entry.getKey();
                    source = "agent";
                    reason = answer[1];
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = pack.entrySet().stream()
                    .max(Comparator.comparingInt(e -> rarityRank(e.getValue().getRarity())))
                    .get().getKey();
        }

        CardInfo card = pack.get(chosen);
        session().sendCardPick(draftId, chosen, new HashSet<>());
        synchronized (this) {
            picks.add(card);
            currentPack.clear();
        }
        logEvent("pick", Map.of("source", source, "card", card.getName(), "reason", reason,
                "pack", names(pack.values()), "pickIndex", picks.size()));
    }

    /** Draft tool: submit a pick and wait for the next decision. */
    public State submitPick(String cardName, String reason) {
        int before;
        synchronized (this) {
            before = picks.size();
            if (currentPack.isEmpty()) {
                State state = state();
                state.error = "No pick is pending. Call get_draft_state to wait for your next pack.";
                return state;
            }
            boolean known = currentPack.values().stream().anyMatch(c -> c.getName().equalsIgnoreCase(String.valueOf(cardName).trim()));
            if (!known) {
                State state = state();
                state.error = "'" + cardName + "' is not in your current pack. Use an exact name from `pack`.";
                return state;
            }
        }
        if (!pickAnswers.offer(new String[]{cardName, reason == null ? "" : reason})) {
            sleep(300);
            pickAnswers.offer(new String[]{cardName, reason == null ? "" : reason});
        }
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until) {
            synchronized (this) {
                if (picks.size() > before) {
                    break;
                }
            }
            sleep(100);
        }
        return awaitDecision(120);
    }

    // ------------------------------------------------------------------ build

    private void construct(TableClientMessage message) throws InterruptedException {
        List<CardInfo> cards = new ArrayList<>();
        for (SimpleCardView card : message.getDeck().getCards().values()) {
            cards.add(CardRepository.instance.findCard(card.getExpansionSetCode(), card.getCardNumber()));
        }
        for (SimpleCardView card : message.getDeck().getSideboard().values()) {
            cards.add(CardRepository.instance.findCard(card.getExpansionSetCode(), card.getCardNumber()));
        }
        UUID tableId = message.getCurrentTableId();
        synchronized (this) {
            phase = "building";
            pool.clear();
            pool.addAll(cards);
            deckAnswers.clear();
            lastDeckResult = null;
        }
        logEvent("construct", Map.of("pool", names(cards), "timeSec", message.getTime()));

        // Auto-build shortly before the server's construction deadline.
        int timeSec = message.getTime() > 0 ? message.getTime() : 600;
        long deadline = System.currentTimeMillis() + Math.max(10, timeSec - 30) * 1000L;
        DeckCardLists deck = null;
        String source = "auto";
        String reason = "";
        while (deck == null && System.currentTimeMillis() < deadline) {
            String[] answer = deckAnswers.poll(Math.min(deadline - System.currentTimeMillis(), 60_000), TimeUnit.MILLISECONDS);
            if (answer == null) {
                continue;
            }
            String problem = deckProblem(answer[0], cards);
            if (problem == null) {
                deck = parseDeck(answer[0], cards);
                source = "agent";
                reason = answer[1];
            } else {
                synchronized (this) {
                    lastDeckResult = "REJECTED: " + problem;
                }
                logEvent("deck_rejected", Map.of("submitted", answer[0], "problem", problem));
            }
        }
        if (deck == null) {
            deck = autoDeck(cards);
        }
        boolean ok = session().submitDeck(tableId, deck);
        synchronized (this) {
            submittedDeck = deck;
            phase = "deck_submitted";
            lastDeckResult = ok ? "ACCEPTED" : "REJECTED by server";
        }
        logEvent("submit_deck", Map.of("source", source, "accepted", ok, "reason", reason,
                "main", deck.getCards().stream().map(c -> c.getAmount() + " " + c.getCardName()).collect(Collectors.toList())));
        writeDeckFile(deck);
    }

    /** Draft tool: submit a decklist ("<count> <name>" per line) for validation and submission. */
    public State submitDeckList(String decklist, String reason) {
        synchronized (this) {
            if (!"building".equals(phase)) {
                State state = state();
                state.error = "Not building a deck right now (phase " + phase + ").";
                return state;
            }
            lastDeckResult = null;
        }
        if (!deckAnswers.offer(new String[]{decklist == null ? "" : decklist, reason == null ? "" : reason})) {
            sleep(300);
            deckAnswers.offer(new String[]{decklist == null ? "" : decklist, reason == null ? "" : reason});
        }
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            synchronized (this) {
                if (lastDeckResult != null) {
                    break;
                }
            }
            sleep(100);
        }
        return state();
    }

    /** Returns null if the decklist is acceptable, otherwise why not. */
    private static String deckProblem(String text, List<CardInfo> pool) {
        Map<String, Integer> available = new HashMap<>();
        for (CardInfo c : pool) {
            available.merge(c.getName().toLowerCase(), 1, Integer::sum);
        }
        Map<String, Integer> wanted = parseCounts(text);
        if (wanted == null) {
            return "every line must be '<count> <card name>'";
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            total += e.getValue();
            if (BASICS.stream().anyMatch(b -> b.equalsIgnoreCase(e.getKey()))) {
                continue;
            }
            int have = available.getOrDefault(e.getKey().toLowerCase(), 0);
            if (have < e.getValue()) {
                return "you have " + have + " '" + e.getKey() + "' in your pool but asked for " + e.getValue();
            }
        }
        return total < 40 ? "deck has " + total + " cards; Limited decks need at least 40" : null;
    }

    private static Map<String, Integer> parseCounts(String text) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length < 2) {
                return null;
            }
            try {
                wanted.merge(parts[1].trim(), Integer.parseInt(parts[0].replace("x", "")), Integer::sum);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return wanted;
    }

    private DeckCardLists parseDeck(String text, List<CardInfo> pool) {
        DeckCardLists deck = new DeckCardLists();
        for (Map.Entry<String, Integer> e : parseCounts(text).entrySet()) {
            CardInfo info = BASICS.stream().anyMatch(b -> b.equalsIgnoreCase(e.getKey()))
                    ? basicLand(e.getKey())
                    : pool.stream().filter(c -> c.getName().equalsIgnoreCase(e.getKey())).findFirst().get();
            deck.getCards().add(new DeckCardInfo(info.getName(), info.getCardNumber(), info.getSetCode(), e.getValue()));
        }
        return deck;
    }

    /** Fallback: two most common colours, best 23 spells by rarity, 17 basics. */
    private DeckCardLists autoDeck(List<CardInfo> pool) {
        Map<Character, Integer> colourCounts = new HashMap<>();
        for (CardInfo c : pool) {
            for (char symbol : c.getColor().toString().toCharArray()) {
                colourCounts.merge(symbol, 1, Integer::sum);
            }
        }
        List<Character> colours = colourCounts.entrySet().stream()
                .sorted(Map.Entry.<Character, Integer>comparingByValue().reversed())
                .limit(2).map(Map.Entry::getKey).collect(Collectors.toList());
        List<CardInfo> spells = pool.stream()
                .filter(c -> !c.getTypes().contains(CardType.LAND))
                .filter(c -> c.getColor().toString().chars().allMatch(ch -> colours.contains((char) ch)))
                .sorted(Comparator.comparingInt((CardInfo c) -> rarityRank(c.getRarity())).reversed())
                .limit(23).collect(Collectors.toList());
        DeckCardLists deck = new DeckCardLists();
        for (CardInfo c : spells) {
            deck.getCards().add(new DeckCardInfo(c.getName(), c.getCardNumber(), c.getSetCode(), 1));
        }
        int lands = 40 - spells.size();
        Map<Character, String> basicFor = Map.of('W', "Plains", 'U', "Island", 'B', "Swamp", 'R', "Mountain", 'G', "Forest");
        List<Character> landColours = colours.isEmpty() ? List.of('W') : colours;
        for (int i = 0; i < landColours.size(); i++) {
            int count = lands / landColours.size() + (i < lands % landColours.size() ? 1 : 0);
            CardInfo basic = basicLand(basicFor.get(landColours.get(i)));
            deck.getCards().add(new DeckCardInfo(basic.getName(), basic.getCardNumber(), basic.getSetCode(), count));
        }
        return deck;
    }

    private CardInfo basicLand(String name) {
        String proper = name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
        List<String> codes;
        synchronized (this) {
            codes = new ArrayList<>(setCodes);
        }
        List<CardInfo> found = codes.isEmpty() ? List.of()
                : CardRepository.instance.findCards(new CardCriteria().name(proper).setCodes(codes));
        if (found.isEmpty()) {
            found = CardRepository.instance.findCards(proper);
        }
        return found.get(0);
    }

    private void writeDeckFile(DeckCardLists deck) {
        if (deckOutPath == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (DeckCardInfo c : deck.getCards()) {
            sb.append(c.getAmount()).append(" [").append(c.getSetCode()).append(":").append(c.getCardNumber())
                    .append("] ").append(c.getCardName()).append("\n");
        }
        try {
            Files.writeString(Path.of(deckOutPath), sb.toString());
        } catch (IOException e) {
            logger.error("Could not write deck file " + deckOutPath, e);
        }
    }

    // ------------------------------------------------------------------ queries

    /** Blocks until there is a decision (pick or build) or the tournament moved on, then returns the state. */
    public State awaitDecision(int maxSeconds) {
        long until = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < until) {
            synchronized (this) {
                boolean decision = !currentPack.isEmpty() || "building".equals(phase);
                boolean beyondDraft = "deck_submitted".equals(phase) || "playing".equals(phase)
                        || "tournament_over".equals(phase);
                if (decision || beyondDraft) {
                    return state();
                }
            }
            sleep(200);
        }
        return state();
    }

    public synchronized State state() {
        State s = new State();
        s.phase = phase;
        s.pick_pending = !currentPack.isEmpty();
        s.set_codes = new ArrayList<>(setCodes);
        s.pack_number = packNumber;
        s.pick_number = pickNumber;
        if (!currentPack.isEmpty() && pickDeadline != null) {
            s.seconds_left_to_pick = (int) Math.max(0, pickDeadline.getEpochSecond() - Instant.now().getEpochSecond());
        }
        s.pack = currentPack.values().stream().map(BridgeDraftSeat::cardJson).collect(Collectors.toList());
        s.picks = names(picks);
        if (!picks.isEmpty()) {
            s.last_pick = picks.get(picks.size() - 1).getName();
        }
        if ("building".equals(phase)) {
            s.pool = pool.stream().map(BridgeDraftSeat::cardJson).collect(Collectors.toList());
            s.deck_rules = "Submit at least 40 cards: cards from your pool (respect counts) plus any number of "
                    + "basic lands (Plains, Island, Swamp, Mountain, Forest), one '<count> <card name>' per line.";
        }
        s.deck_result = lastDeckResult;
        s.standings = finalStandings;
        return s;
    }

    /** All cards of the draft's sets, one entry per name. */
    public List<Map<String, Object>> setCards(String setCode) {
        List<String> codes;
        synchronized (this) {
            codes = setCode != null && !setCode.isBlank() ? List.of(setCode) : new ArrayList<>(new java.util.LinkedHashSet<>(setCodes));
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<String, CardInfo> byName = new LinkedHashMap<>();
        for (CardInfo c : CardRepository.instance.findCards(new CardCriteria().setCodes(codes))) {
            byName.putIfAbsent(c.getName(), c);
        }
        return byName.values().stream().map(BridgeDraftSeat::cardJson).collect(Collectors.toList());
    }

    public static Map<String, Object> cardJson(CardInfo c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.getName());
        m.put("mana_cost", String.join("", c.getManaCosts(CardInfo.ManaCostSide.ALL)));
        m.put("mana_value", c.getManaValue());
        m.put("types", c.getTypes().stream().map(CardType::toString).collect(Collectors.toList()));
        m.put("subtypes", c.getSubTypes().stream().map(Object::toString).collect(Collectors.toList()));
        m.put("rarity", c.getRarity().toString());
        if (c.getPower() != null && !c.getPower().isEmpty()) {
            m.put("pt", c.getPower() + "/" + c.getToughness());
        }
        m.put("rules", c.getRules());
        return m;
    }

    // ------------------------------------------------------------------ tournament

    private void startStandingsWatcher() {
        Thread.ofVirtual().name("bridge-tournament-watcher").start(() -> {
            while (client.isRunning()) {
                sleep(15_000);
                UUID tid;
                synchronized (this) {
                    tid = tournamentId;
                }
                try {
                    TournamentView view = session().getTournament(tid);
                    if (view != null && view.getEndTime() != null) {
                        List<Map<String, Object>> standings = standings(view);
                        synchronized (this) {
                            phase = "tournament_over";
                            finalStandings = standings;
                        }
                        logEvent("tournament_over", Map.of("standings", standings));
                        // Let the agent read the standings before the bridge shuts down.
                        sleep(Integer.getInteger("xmage.bridge.tournamentLingerMs", 60_000));
                        client.setRunning(false);
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("[" + client.getUsername() + "] standings poll failed: " + e.getMessage());
                }
            }
        });
    }

    private static List<Map<String, Object>> standings(TournamentView view) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (TournamentPlayerView p : view.getPlayers()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getName());
            row.put("points", p.getPoints());
            row.put("results", String.valueOf(p.getResults()));
            row.put("state", String.valueOf(p.getState()));
            players.add(row);
        }
        players.sort(Comparator.comparingInt((Map<String, Object> p) -> (Integer) p.get("points")).reversed());
        return players;
    }

    /** Called when a tournament game starts for this seat. */
    public synchronized void markPlaying() {
        phase = "playing";
    }

    // ------------------------------------------------------------------ helpers

    public void logEvent(String type, Map<String, ?> data) {
        if (log == null) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("type", type);
        event.putAll(data);
        synchronized (log) {
            log.println(GSON.toJson(event));
        }
    }

    private static List<String> names(Collection<CardInfo> cards) {
        return cards.stream().map(CardInfo::getName).collect(Collectors.toList());
    }

    private static int rarityRank(Rarity rarity) {
        switch (rarity) {
            case MYTHIC: return 4;
            case RARE: return 3;
            case UNCOMMON: return 2;
            case COMMON: return 1;
            default: return 0;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
