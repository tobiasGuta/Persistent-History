package io.persistenthistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
            assertEquals(
                    "Repeater",
                    manager.databaseFor(exampleB.id()).searchMetadata("", 10).getFirst().tool());
        }
    }

    @Test
    void targetParserAcceptsWildcardAndUrlsAndNormalizesThem() {
        List<String> targets = WorkspaceManager.parseTargets(
                "*.Example.COM, https://API.Example.net/login\nsub.example.org.");
        assertEquals(
                List.of("example.com", "api.example.net", "sub.example.org"),
                targets);
    }

    @Test
    void existingV1DatabaseAppearsAsLegacyWithoutMigration() throws Exception {
        Path root = tempDir.resolve("root");
        Path legacyPath = root.resolve("history.sqlite3");
        byte[] request = request("legacy.test");
        byte[] response = response(200);

        try (HistoryDatabase legacy = new HistoryDatabase(legacyPath)) {
            legacy.insert(
                    1L,
                    "Proxy",
                    "GET",
                    "https://legacy.test/",
                    200,
                    request,
                    response);
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
            Workspace selected = manager.createWorkspace(
                    "Selected",
                    List.of("selected.test"));
            selectedId = selected.id();
        }

        try (WorkspaceManager reopened = new WorkspaceManager(root)) {
            assertEquals(selectedId, reopened.activeWorkspace().id());
            assertEquals("Selected", reopened.activeWorkspace().name());
        }
    }

    @Test
    void structuredScopePersistsAcrossRestartAndKeepsExclusions() throws Exception {
        Path root = tempDir.resolve("root");
        String workspaceId;

        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            Workspace workspace = manager.createWorkspace(
                    "Scoped",
                    List.of("example.test"));
            workspaceId = workspace.id();

            WorkspaceScope advanced = new WorkspaceScope(
                    List.of(new ScopeRule(
                            true,
                            "https",
                            "(?:^|.*\\.)example\\.test$",
                            "",
                            "^/api/.*")),
                    List.of(new ScopeRule(
                            true,
                            "any",
                            "",
                            "",
                            "^/api/logout.*")));
            manager.updateScope(workspaceId, advanced);
        }

        try (WorkspaceManager reopened = new WorkspaceManager(root)) {
            reopened.setActiveWorkspace(workspaceId);
            Workspace workspace = reopened.activeWorkspace();

            assertTrue(workspace.captures("https://api.example.test/api/users"));
            assertFalse(workspace.captures("https://api.example.test/api/logout"));
            assertFalse(workspace.captures("https://api.example.test/home"));
            assertTrue(Files.isRegularFile(
                    workspace.databasePath().getParent().resolve("scope.json")));
        }
    }

    @Test
    void v20TargetRootsAreConvertedToStructuredScopeWithoutBroadening() throws Exception {
        Path root = tempDir.resolve("root");
        Path directory = root.resolve("workspaces").resolve("old-v2");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("workspace.properties"),
                "id=old-v2\nname=Old V2\ntargets=example.test\n");

        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            Workspace old = manager.workspaces().stream()
                    .filter(workspace -> workspace.id().equals("old-v2"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(old.captures("https://example.test/"));
            assertTrue(old.captures("https://api.example.test/"));
            assertFalse(old.captures("https://other.test/"));
            assertTrue(Files.isRegularFile(directory.resolve("scope.json")));
        }
    }

    @Test
    void emptyV20WorkspaceRemainsExplicitlyUnscopedAfterUpgrade() throws Exception {
        Path root = tempDir.resolve("root");
        Path directory = root.resolve("workspaces").resolve("old-empty");
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("workspace.properties"),
                "id=old-empty\nname=Old Empty\n");

        try (WorkspaceManager manager = new WorkspaceManager(root)) {
            Workspace old = manager.workspaces().stream()
                    .filter(workspace -> workspace.id().equals("old-empty"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(old.scope().unscoped());
            assertTrue(old.captures("https://anything.test/"));
        }

        try (WorkspaceManager reopened = new WorkspaceManager(root)) {
            Workspace old = reopened.workspaces().stream()
                    .filter(workspace -> workspace.id().equals("old-empty"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(old.scope().unscoped());
            assertTrue(old.captures("https://still-anything.test/"));
        }
    }

    private static byte[] request(String host) {
        return ("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes();
    }

    private static byte[] response(int status) {
        return ("HTTP/1.1 " + status + " Test\r\nContent-Length: 0\r\n\r\n").getBytes();
    }
}
