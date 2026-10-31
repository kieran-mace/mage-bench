package mage.client.bridge;

import mage.client.bridge.listener.BridgeCallbackListener;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.remote.Session;
import mage.utils.MageVersion;
import org.apache.log4j.Logger;

/**
 * Bridge MageClient implementation for bot/AI players.
 * Delegates callback handling to BridgeCallbackHandler.
 */
public class BridgeMageClient implements MageClient {

    private static final Logger logger = Logger.getLogger(BridgeMageClient.class);
    private static final MageVersion version = new MageVersion(BridgeMageClient.class);

    private final String username;
    private final BridgeCallbackListener callbackListener;
    private Session session;
    private volatile BridgeCallbackHandler callbackHandler;
    private volatile boolean running = true;
    private volatile boolean reconnectable = false;
    private volatile boolean suppressDisconnect = false;
    // mtgBench: set when this bridge is seated in a tournament (draft/build/Swiss).
    private volatile mage.client.bridge.draft.BridgeDraftSeat draftSeat;
    private volatile mage.client.bridge.draft.BridgeNotebook notebook;

    public BridgeMageClient(String username) {
        this.username = username;
        this.callbackHandler = new BridgeCallbackHandler(this);
        this.callbackListener = new BridgeCallbackListener(username, logger);
        this.callbackListener.start();
    }

    public void setSession(Session session) {
        this.session = session;
        this.callbackHandler.setSession(session);
    }

    public Session getSession() {
        return session;
    }

    public String getUsername() {
        return username;
    }

    public mage.client.bridge.draft.BridgeDraftSeat getDraftSeat() {
        return draftSeat;
    }

    public void setDraftSeat(mage.client.bridge.draft.BridgeDraftSeat draftSeat) {
        this.draftSeat = draftSeat;
    }

    public synchronized mage.client.bridge.draft.BridgeNotebook getNotebook() {
        if (notebook == null) {
            try {
                notebook = new mage.client.bridge.draft.BridgeNotebook(System.getProperty("xmage.bridge.notebookLog"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot open notebook log", e);
            }
        }
        return notebook;
    }

    public BridgeCallbackHandler getCallbackHandler() {
        return callbackHandler;
    }

    public void setCallbackHandler(BridgeCallbackHandler handler) {
        this.callbackHandler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        callbackListener.shutdown("client.stop()");
        callbackHandler.shutdownProcessor("client.stop()");
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isReconnectable() {
        return reconnectable;
    }

    public void suppressDisconnectCallbacks(boolean suppress) {
        this.suppressDisconnect = suppress;
    }

    @Override
    public MageVersion getVersion() {
        return version;
    }

    @Override
    public void connected(String message) {
        logger.info("[" + username + "] Connected: " + message);
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        if (suppressDisconnect) {
            logger.info("[" + username + "] Disconnected callback suppressed during reconnection");
            return;
        }
        logger.info("[" + username + "] Disconnected (askToReconnect=" + askToReconnect + ", keepSession=" + keepMySessionActive + ")");
        reconnectable = askToReconnect && keepMySessionActive;
        running = false;
    }

    @Override
    public void showMessage(String message) {
        logger.info("[" + username + "] Message: " + message);
    }

    @Override
    public void showError(String message) {
        logger.error("[" + username + "] Error: " + message);
    }

    @Override
    public void onNewConnection() {
        if (reconnectable) {
            logger.info("[" + username + "] Reconnected — preserving game state (skipping reset)");
            reconnectable = false;
        } else {
            logger.info("[" + username + "] New connection established");
            callbackHandler.reset();
        }
    }

    @Override
    public void onCallback(ClientCallback callback) {
        mage.client.bridge.draft.BridgeDraftSeat seat = draftSeat;
        if (seat != null) {
            if (seat.handles(callback.getMethod())) {
                seat.onCallback(callback);
                return;
            }
            if (callback.getMethod() == mage.interfaces.callback.ClientCallbackMethod.START_GAME) {
                // Each tournament game starts on its own table: arm a fresh game handler for it.
                callback.decompressData();
                mage.view.TableClientMessage message = (mage.view.TableClientMessage) callback.getData();
                logger.info("[" + username + "] Tournament game starting on table " + message.getCurrentTableId());
                callbackHandler.createFreshForTournamentGame(message.getCurrentTableId());
                seat.markPlaying();
            }
        }
        callbackListener.enqueue(callbackHandler, callback);
    }

    void awaitCallbackListenerIdle() throws InterruptedException {
        callbackListener.awaitIdle();
    }
}
