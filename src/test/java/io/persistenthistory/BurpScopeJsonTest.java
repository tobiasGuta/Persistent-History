package io.persistenthistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BurpScopeJsonTest {
    @TempDir
    Path tempDir;

    @Test
    void importsAdvancedBurpIncludeAndExcludeRules() throws Exception {
        Path file = tempDir.resolve("burp-scope.json");
        Files.writeString(file, """
                {
                  "target": {
                    "scope": {
                      "advanced_mode": true,
                      "include": [
                        {
                          "enabled": true,
                          "protocol": "https",
                          "host": ".*\\\\.example\\\\.test",
                          "port": "443",
                          "file": "^/api/.*"
                        }
                      ],
                      "exclude": [
                        {
                          "enabled": true,
                          "protocol": "any",
                          "file": "^/api/logout.*"
                        }
                      ]
                    }
                  }
                }
                """);

        WorkspaceScope scope = BurpScopeJson.importScope(file);

        assertEquals(1, scope.include().size());
        assertEquals(1, scope.exclude().size());
        assertFalse(scope.unscoped());
        assertTrue(scope.captures("https://api.example.test/api/users"));
        assertFalse(scope.captures("https://api.example.test/api/logout"));
        assertFalse(scope.captures("http://api.example.test/api/users"));
    }

    @Test
    void importsSimpleModePrefixRules() throws Exception {
        Path file = tempDir.resolve("simple.json");
        Files.writeString(file, """
                {
                  "target": {
                    "scope": {
                      "advanced_mode": false,
                      "include": [
                        {"enabled": true, "prefix": "https://example.test/app"}
                      ],
                      "exclude": []
                    }
                  }
                }
                """);

        WorkspaceScope scope = BurpScopeJson.importScope(file);

        assertTrue(scope.captures("https://example.test/app"));
        assertTrue(scope.captures("https://example.test/app/users"));
        assertFalse(scope.captures("https://example.test/other"));
        assertFalse(scope.captures("http://example.test/app"));
        assertFalse(scope.captures("https://example.test:8443/app"));
    }

    @Test
    void savedWorkspaceScopeRoundTripsThroughBurpCompatibleJson() throws Exception {
        WorkspaceScope original = new WorkspaceScope(
                java.util.List.of(new ScopeRule(true, "https", "api\\.example\\.test", "443", "^/v1/.*")),
                java.util.List.of(new ScopeRule(false, "any", "", "", "^/v1/ignore.*")));
        Path file = tempDir.resolve("scope.json");

        BurpScopeJson.writeScope(file, original);
        WorkspaceScope restored = BurpScopeJson.importScope(file);

        assertEquals(original.include(), restored.include());
        assertEquals(original.exclude(), restored.exclude());
    }

    @Test
    void rejectsJsonWithoutTargetScopeRules() throws Exception {
        Path file = tempDir.resolve("wrong.json");
        Files.writeString(file, "{\"proxy\":{\"request_listeners\":[]}}");

        assertThrows(IllegalArgumentException.class, () -> BurpScopeJson.importScope(file));
    }
}
