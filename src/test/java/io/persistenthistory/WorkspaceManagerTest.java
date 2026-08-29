package io.persistenthistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void scopedWorkspaceAcceptsRootAndSubdomainsButRejectsOtherTargets() throws Exception {
        try (WorkspaceManager manager = new WorkspaceManager(tempDir.resolve("root"))) {
            Workspace workspace = manager.createWorkspace("Example A", List.of("example-a.test"));

            assertTrue(workspace.captures("https://example-a.test/"));
            assertTrue(workspace.captures("https://api.example-a.test/v1"));
            assertFalse(workspace.captures("https://example-b.test/"));
            assertFalse(workspace.captures("https://notexample-a.test/"));
        }
    }

    @Test
    void twoProgramWorkspacesPersistIntoDifferentDatabases() throws Exception {
        try (WorkspaceManager manager = new WorkspaceManager(tempDir.resolve("root"))) {
            Workspace exampleA = manager.createWorkspace("Example A", List.of("example-a.test"));
            Workspace exampleB = manager.createWorkspace("Example B", List.of("example-b.test"));

            manager.setActiveWorkspace(exampleA.id());
            WorkspaceManager.CaptureResult storedA = manager.capture(
                    1L, "Proxy", "GET", "https://api.example-a.test/me", 200,
                    request("api.example-a.test"), response(200));
            WorkspaceManager.CaptureResult skippedB = manager.capture(
                    2L, "Proxy", "GET", "https://example-b.test/me", 200,
                    request("example-b.test"), response(200));

            assertNotNull(storedA);
            assertNull(skippedB);
            assertEquals(1, manager.databaseFor(exampleA.id()).count());
            assertEquals(0, manager.databaseFor(exampleB.id()).count());
            assertEquals(1, manager.skippedOutsideTargets(exampleA.id()));

            manager.setActiveWorkspace(exampleB.id());
            WorkspaceManager.CaptureResult storedB = manager.capture(
                    3L, "Repeater", "POST", "https://auth.example-b.test/session", 403,
                    request("auth.example-b.test"), response(403));

            assertNotNull(storedB);
            assertEquals(exampleB.id(), storedB.workspaceId());
            assertEquals(1, manager.databaseFor(exampleA.id()).count());
            assertEquals(1, manager.databaseFor(exampleB.id()).count());
            assertEquals("Repeater", manager.databaseFor(exampleB.id()).searchMetadata("", 10).getFirst().tool());
        }
    }

    @Test
    void targetParserAcceptsWildcardAndUrlsAndNormalizesThem() {
        List<String> targets = WorkspaceManager.parseTargets("*.Example.COM, https://API.Example.net/login\nsub.example.org.");
        assertEquals(List.of("example.com", "api.example.net", "sub.example.org"), targets);
    }

    @Test
    void existingV1DatabaseAppearsAsLegacyWithoutMigration() throws Exception {
        Path root = tempDir.resolve("root");
        Path legacyPath = root.resolve("history.sqlite3");
        byte[] request = request("legacy.test");
        byte[] response = response(200);

        try (HistoryDatabase legacy = new HistoryDatabase(legacyPath)) {
            legacy.insert(1L, "Proxy", "GET", "https://legacy.test/", 200, request, response);
        }

        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            Workspace active = manager.activeWorkspace();
            assertTrue(active.legacy());
            assertEquals("Legacy / Unscoped", active.name());
            assertEquals(1, manager.databaseFor(active.id()).count());
        }
    }

    @Test
    void activeWorkspaceSelectionSurvivesManagerRestart() throws Exception {
        Path root = tempDir.resolve("root");
        String selectedId;
        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            Workspace selected = manager.createWorkspace("Selected", List.of("selected.test"));
            selectedId = selected.id();
        }

        try (WorkspaceManager reopened = new WorkspaceManager(root)) {
            assertEquals(selectedId, reopened.activeWorkspace().id());
            assertEquals("Selected", reopened.activeWorkspace().name());
        }
    }

    private static byte[] request(String host) {
        return ("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes();
    }

    private static byte[] response(int status) {
        return ("HTTP/1.1 " + status + " Test\r\nContent-Length: 0\r\n\r\n").getBytes();
    }
}
