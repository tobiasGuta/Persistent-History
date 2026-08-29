package io.persistenthistory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeRuleTest {
    @Test
    void includeAndExcludeRulesApplyProtocolHostPortAndPath() {
        ScopeRule include = new ScopeRule(
                true,
                "https",
                "(?:^|.*\\.)example\\.test$",
                "443",
                "^/api/.*");
        ScopeRule exclude = new ScopeRule(
                true,
                "any",
                "",
                "",
                "^/api/logout.*");

        WorkspaceScope scope = new WorkspaceScope(List.of(include), List.of(exclude));

        assertTrue(scope.captures("https://api.example.test/api/users"));
        assertFalse(scope.captures("http://api.example.test/api/users"));
        assertFalse(scope.captures("https://api.example.test/api/logout"));
        assertFalse(scope.captures("https://other.test/api/users"));
    }

    @Test
    void targetRootCompatibilityStillIncludesRootAndSubdomains() {
        WorkspaceScope scope = WorkspaceScope.fromTargetRoots(List.of("example.test"));

        assertTrue(scope.captures("https://example.test/"));
        assertTrue(scope.captures("https://api.example.test/"));
        assertFalse(scope.captures("https://notexample.test/"));
    }

    @Test
    void scopedModeRequiresAnIncludeRuleLikeBurpAdvancedScope() {
        WorkspaceScope scope = new WorkspaceScope(
                List.of(),
                List.of(new ScopeRule(true, "any", "tracking\\.test", "", "")));

        assertFalse(scope.captures("https://example.test/"));
        assertFalse(scope.captures("https://tracking.test/pixel"));
    }

    @Test
    void explicitUnscopedModePreservesV2CaptureAllBehavior() {
        WorkspaceScope scope = new WorkspaceScope(
                List.of(),
                List.of(new ScopeRule(true, "any", "tracking\\.test", "", "")),
                true);

        assertTrue(scope.captures("https://example.test/"));
        assertTrue(scope.captures("https://tracking.test/pixel"));
    }

    @Test
    void burpStyleIpv4CidrAndOctetRangesAreSupported() {
        ScopeRule cidr = new ScopeRule(true, "any", "10.20.30.0/24", "", "");
        ScopeRule range = new ScopeRule(true, "any", "10.20.1-3.5-7", "", "");

        assertTrue(cidr.matches("https://10.20.30.42/"));
        assertFalse(cidr.matches("https://10.20.31.42/"));
        assertTrue(range.matches("http://10.20.2.6/"));
        assertFalse(range.matches("http://10.20.4.6/"));
    }

    @Test
    void invalidRegexIsRejectedBeforeItCanReachCapturePath() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ScopeRule(true, "any", "[", "", ""));
        assertTrue(error.getMessage().contains("Invalid host regex"));
    }
}
