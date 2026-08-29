package io.persistenthistory;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentHttpHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void sameMontoyaMessageIdIsPersistedOnlyOnce() throws Exception {
        try (WorkspaceManager manager = scopedManager()) {
            HistoryDatabase db = manager.databaseFor(manager.activeWorkspace().id());
            AtomicInteger notifications = new AtomicInteger();
            PersistentHttpHandler handler = new PersistentHttpHandler(manager, ignored -> notifications.incrementAndGet());
            HttpResponseReceived received = response(101, ToolType.PROXY, "https://example.test/submit");

            try (MockedStatic<ResponseReceivedAction> actions = mockStatic(ResponseReceivedAction.class)) {
                actions.when(() -> ResponseReceivedAction.continueWith(received)).thenReturn(mock(ResponseReceivedAction.class));
                handler.handleHttpResponseReceived(received);
                handler.handleHttpResponseReceived(received);
            }

            assertEquals(1, db.count());
            assertEquals(1, notifications.get());
        }
    }

    @Test
    void identicalTrafficWithDifferentMessageIdsIsNotOverDeduplicated() throws Exception {
        try (WorkspaceManager manager = scopedManager()) {
            HistoryDatabase db = manager.databaseFor(manager.activeWorkspace().id());
            PersistentHttpHandler handler = new PersistentHttpHandler(manager, ignored -> {});
            HttpResponseReceived first = response(201, ToolType.REPEATER, "https://example.test/submit");
            HttpResponseReceived second = response(202, ToolType.REPEATER, "https://example.test/submit");

            try (MockedStatic<ResponseReceivedAction> actions = mockStatic(ResponseReceivedAction.class)) {
                ResponseReceivedAction continued = mock(ResponseReceivedAction.class);
                actions.when(() -> ResponseReceivedAction.continueWith(first)).thenReturn(continued);
                actions.when(() -> ResponseReceivedAction.continueWith(second)).thenReturn(continued);
                handler.handleHttpResponseReceived(first);
                handler.handleHttpResponseReceived(second);
            }

            assertEquals(2, db.count());
        }
    }

    @Test
    void repeaterTrafficKeepsRepeaterProvenance() throws Exception {
        try (WorkspaceManager manager = scopedManager()) {
            HistoryDatabase db = manager.databaseFor(manager.activeWorkspace().id());
            PersistentHttpHandler handler = new PersistentHttpHandler(manager, ignored -> {});
            HttpResponseReceived received = response(301, ToolType.REPEATER, "https://example.test/submit");

            try (MockedStatic<ResponseReceivedAction> actions = mockStatic(ResponseReceivedAction.class)) {
                actions.when(() -> ResponseReceivedAction.continueWith(received)).thenReturn(mock(ResponseReceivedAction.class));
                handler.handleHttpResponseReceived(received);
            }

            HistoryEntry row = db.searchMetadata("Repeater", 10).getFirst();
            assertEquals("Repeater", row.tool());
            assertEquals("POST", row.method());
            assertEquals(201, row.status());
        }
    }

    @Test
    void pausedCaptureDoesNotPersist() throws Exception {
        try (WorkspaceManager manager = scopedManager()) {
            HistoryDatabase db = manager.databaseFor(manager.activeWorkspace().id());
            PersistentHttpHandler handler = new PersistentHttpHandler(manager, ignored -> fail("capture callback should not run"));
            handler.setEnabled(false);
            HttpResponseReceived received = response(401, ToolType.PROXY, "https://example.test/submit");

            try (MockedStatic<ResponseReceivedAction> actions = mockStatic(ResponseReceivedAction.class)) {
                actions.when(() -> ResponseReceivedAction.continueWith(received)).thenReturn(mock(ResponseReceivedAction.class));
                handler.handleHttpResponseReceived(received);
            }

            assertEquals(0, db.count());
        }
    }

    @Test
    void requestOutsideWorkspaceTargetsIsNotPersisted() throws Exception {
        try (WorkspaceManager manager = scopedManager()) {
            HistoryDatabase db = manager.databaseFor(manager.activeWorkspace().id());
            AtomicInteger notifications = new AtomicInteger();
            PersistentHttpHandler handler = new PersistentHttpHandler(manager, ignored -> notifications.incrementAndGet());
            HttpResponseReceived received = response(501, ToolType.PROXY, "https://other-target.test/submit");

            try (MockedStatic<ResponseReceivedAction> actions = mockStatic(ResponseReceivedAction.class)) {
                actions.when(() -> ResponseReceivedAction.continueWith(received)).thenReturn(mock(ResponseReceivedAction.class));
                handler.handleHttpResponseReceived(received);
            }

            assertEquals(0, db.count());
            assertEquals(0, notifications.get());
            assertEquals(1, manager.skippedOutsideTargets(manager.activeWorkspace().id()));
        }
    }

    private WorkspaceManager scopedManager() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(tempDir.resolve("root-" + System.nanoTime()));
        manager.createWorkspace("Example", List.of("example.test"));
        return manager;
    }

    private static HttpResponseReceived response(int messageId, ToolType toolType, String url) {
        byte[] requestBytes = "POST /submit HTTP/1.1\r\nHost: example.test\r\nContent-Length: 2\r\n\r\n{}".getBytes();
        byte[] responseBytes = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".getBytes();

        ByteArray requestArray = mock(ByteArray.class);
        when(requestArray.getBytes()).thenReturn(requestBytes);
        ByteArray responseArray = mock(ByteArray.class);
        when(responseArray.getBytes()).thenReturn(responseBytes);

        HttpRequest request = mock(HttpRequest.class);
        when(request.toByteArray()).thenReturn(requestArray);
        when(request.method()).thenReturn("POST");
        when(request.url()).thenReturn(url);

        ToolSource toolSource = mock(ToolSource.class);
        when(toolSource.toolType()).thenReturn(toolType);

        HttpResponseReceived response = mock(HttpResponseReceived.class);
        when(response.messageId()).thenReturn(messageId);
        when(response.initiatingRequest()).thenReturn(request);
        when(response.toByteArray()).thenReturn(responseArray);
        when(response.toolSource()).thenReturn(toolSource);
        when(response.statusCode()).thenReturn((short) 201);
        return response;
    }
}
