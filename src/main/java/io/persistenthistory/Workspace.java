package io.persistenthistory;

import java.nio.file.Path;
import java.util.List;

public record Workspace(
        String id,
        String name,
        List<String> targets,
        WorkspaceScope scope,
        Path databasePath,
        boolean legacy
) {
    public Workspace {
        targets = List.copyOf(targets == null ? List.of() : targets);
        scope = scope == null ? WorkspaceScope.fromTargetRoots(targets) : scope;
    }

    public Workspace(String id, String name, List<String> targets, Path databasePath, boolean legacy) {
        this(id, name, targets, WorkspaceScope.fromTargetRoots(targets), databasePath, legacy);
    }

    public boolean captures(String url) {
        return scope.captures(url);
    }

    public boolean scoped() {
        return scope.scoped();
    }

    public String scopeLabel() {
        return scope.summary();
    }

    @Override
    public String toString() {
        return name;
    }
}
