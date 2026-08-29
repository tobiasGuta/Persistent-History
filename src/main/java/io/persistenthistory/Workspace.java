package io.persistenthistory;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public record Workspace(
        String id,
        String name,
        List<String> targets,
        Path databasePath,
        boolean legacy
) {
    public Workspace {
        targets = List.copyOf(targets == null ? List.of() : targets);
    }

    public boolean captures(String url) {
        if (targets.isEmpty()) {
            return true;
        }
        return RequestTarget.fromUrl(url)
                .map(target -> matchesHost(target.host()))
                .orElse(false);
    }

    public boolean scoped() {
        return !targets.isEmpty();
    }

    public String scopeLabel() {
        return scoped() ? String.join(", ", targets) : "All targets (unscoped)";
    }

    private boolean matchesHost(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String host = value.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (String root : targets) {
            if (host.equals(root) || host.endsWith("." + root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
