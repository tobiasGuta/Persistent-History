package io.persistenthistory;

import burp.api.montoya.http.handler.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PersistentHttpHandler implements HttpHandler {
    private final HistoryDatabase db;
    private final Consumer<HistoryEntry> onCaptured;
    private final Set<Integer> capturedMessageIds = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    public PersistentHttpHandler(HistoryDatabase db, Consumer<HistoryEntry> onCaptured) {
        this.db = db;
        this.onCaptured = onCaptured;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        if (!enabled) {
            return ResponseReceivedAction.continueWith(responseReceived);
        }

        // A request and its response share the same Montoya message ID. Keep an
        // in-memory set for this Burp session so an accidental duplicate callback
        // cannot persist the same exchange twice. The set resets when the extension
        // reloads, avoiding collisions if Burp reuses IDs in a later session.
        int messageId = responseReceived.messageId();
        if (!capturedMessageIds.add(messageId)) {
            return ResponseReceivedAction.continueWith(responseReceived);
        }

        try {
            byte[] request = responseReceived.initiatingRequest().toByteArray().getBytes();
            byte[] response = responseReceived.toByteArray().getBytes();
            long now = System.currentTimeMillis();
            String tool = responseReceived.toolSource().toolType().toString();
            String method = responseReceived.initiatingRequest().method();
            String url = responseReceived.initiatingRequest().url();
            int status = responseReceived.statusCode();
            long id = db.insert(now, tool, method, url, status, request, response);
            onCaptured.accept(new HistoryEntry(id, now, tool, method, url, status, request, response));
        } catch (Exception ignored) {
            capturedMessageIds.remove(messageId);
            // Persistence failure must never interfere with the user's Burp traffic.
        }

        return ResponseReceivedAction.continueWith(responseReceived);
    }
}
