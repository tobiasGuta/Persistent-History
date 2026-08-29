package io.persistenthistory;

import java.util.List;

public record WorkspaceScope(
        List<ScopeRule> include,
        List<ScopeRule> exclude,
        boolean unscoped
) {
    public WorkspaceScope {
        include = List.copyOf(include == null ? List.of() : include);
        exclude = List.copyOf(exclude == null ? List.of() : exclude);
    }

    public WorkspaceScope(List<ScopeRule> include, List<ScopeRule> exclude) {
        this(include, exclude, false);
    }

    public static WorkspaceScope unscoped() {
        return new WorkspaceScope(List.of(), List.of(), true);
    }

    public static WorkspaceScope fromTargetRoots(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return unscoped();
        }
        return new WorkspaceScope(
                roots.stream().map(ScopeRule::fromTargetRoot).toList(),
                List.of(),
                false);
    }

    public boolean captures(String url) {
        if (unscoped) {
            return true;
        }

        boolean included = include.stream().anyMatch(rule -> rule.matches(url));
        if (!included) {
            return false;
        }
        return exclude.stream().noneMatch(rule -> rule.matches(url));
    }

    public boolean scoped() {
        return !unscoped;
    }

    public String summary() {
        if (unscoped) {
            return "All (unscoped)";
        }
        return include.size() + " include / " + exclude.size() + " exclude";
    }
}
