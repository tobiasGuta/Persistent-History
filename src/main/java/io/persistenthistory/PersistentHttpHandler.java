package io.persistenthistory;

import burp.api.montoya.http.handler.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PersistentHttpHandler implements HttpHandler {
    private final WorkspaceManager workspaces;
    private final Consumer<WorkspaceManager.CaptureResult> onCaptured;
    private final Set<Integer> capturedMessageIds = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    public PersistentHttpHandler(WorkspaceManager workspaces, Consumer<WorkspaceManager.CaptureResult> onCaptured) {
        this.workspaces = workspaces;
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

            WorkspaceManager.CaptureResult result = workspaces.capture(
                    now, tool, method, url, status, request, response);
            if (result != null) {
                onCaptured.accept(result);
            }
        } catch (Exception ignored) {
            capturedMessageIds.remove(messageId);
            // Persistence failure must never interfere with the user's Burp traffic.
        }

        return ResponseReceivedAction.continueWith(responseReceived);
    }
}
