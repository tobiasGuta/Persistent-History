package io.persistenthistory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public final class WorkspaceManager implements AutoCloseable {
    private static final String LEGACY_ID = "legacy";
    private static final String SCOPE_FILE = "scope.json";

    private final Path root;
    private final Path workspacesRoot;
    private final Path activeWorkspaceFile;
    private final Map<String, Context> contexts = new LinkedHashMap<>();
    private volatile String activeWorkspaceId;

    public WorkspaceManager(Path root) throws Exception {
        this.root = root.toAbsolutePath();
        this.workspacesRoot = this.root.resolve("workspaces");
        this.activeWorkspaceFile = this.root.resolve("active-workspace.txt");
        Files.createDirectories(this.root);
        Files.createDirectories(this.workspacesRoot);

        loadLegacyWorkspace();
        loadManagedWorkspaces();

        if (contexts.isEmpty()) {
            Workspace created = createWorkspaceInternal("Default / Unscoped", List.of());
            activeWorkspaceId = created.id();
            persistActiveWorkspace();
        } else {
            String saved = readSavedActiveWorkspace();
            if (saved != null && contexts.containsKey(saved)) {
                activeWorkspaceId = saved;
            } else if (contexts.containsKey(LEGACY_ID)) {
                activeWorkspaceId = LEGACY_ID;
            } else {
                activeWorkspaceId = contexts.keySet().iterator().next();
            }
        }
    }

    public Path root() {
        return root;
    }

    public synchronized List<Workspace> workspaces() {
        return contexts.values().stream()
                .map(context -> context.workspace)
                .sorted(Comparator
                        .comparing(Workspace::legacy).reversed()
                        .thenComparing(Workspace::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public synchronized Workspace activeWorkspace() {
        Context context = contexts.get(activeWorkspaceId);
        if (context == null) {
            throw new IllegalStateException("Active workspace is unavailable: " + activeWorkspaceId);
        }
        return context.workspace;
    }

    public synchronized void setActiveWorkspace(String workspaceId) throws Exception {
        if (!contexts.containsKey(workspaceId)) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }
        activeWorkspaceId = workspaceId;
        persistActiveWorkspace();
    }

    public synchronized Workspace createWorkspace(String name, List<String> targets) throws Exception {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("Workspace name cannot be empty.");
        }
        Workspace workspace = createWorkspaceInternal(cleanName, normalizeTargets(targets));
        activeWorkspaceId = workspace.id();
        persistActiveWorkspace();
        return workspace;
    }

    public synchronized Workspace updateTargets(String workspaceId, List<String> targets) throws Exception {
        List<String> normalized = normalizeTargets(targets);
        return updateScopeInternal(
                workspaceId,
                normalized,
                WorkspaceScope.fromTargetRoots(normalized));
    }

    public synchronized Workspace updateScope(String workspaceId, WorkspaceScope scope) throws Exception {
        if (scope == null) {
            throw new IllegalArgumentException("Workspace scope cannot be null.");
        }
        return updateScopeInternal(workspaceId, List.of(), scope);
    }

    public CaptureResult capture(long capturedAt, String tool, String method, String url,
                                 int status, byte[] request, byte[] response) throws SQLException {
        Context context = activeContext();
        Workspace workspace = context.workspace;
        if (!workspace.captures(url)) {
            context.skippedOutsideTargets.incrementAndGet();
            return null;
        }

        long id = context.database.insert(capturedAt, tool, method, url, status, request, response);
        HistoryEntry entry = new HistoryEntry(id, capturedAt, tool, method, url, status, request, response);
        return new CaptureResult(workspace.id(), entry);
    }

    public long skippedOutsideTargets(String workspaceId) {
        Context context;
        synchronized (this) {
            context = contexts.get(workspaceId);
        }
        return context == null ? 0 : context.skippedOutsideTargets.get();
    }

    HistoryDatabase databaseFor(String workspaceId) {
        Context context;
        synchronized (this) {
            context = contexts.get(workspaceId);
        }
        if (context == null) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }
        return context.database;
    }

    static List<String> parseTargets(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] pieces = text.split("[,;\\r\\n\\t ]+");
        List<String> values = new ArrayList<>();
        for (String piece : pieces) {
            if (!piece.isBlank()) {
                values.add(piece);
            }
        }
        return normalizeTargets(values);
    }

    static List<String> normalizeTargets(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);

            if (value.startsWith("http://") || value.startsWith("https://")) {
                try {
                    String host = URI.create(value).getHost();
                    if (host == null || host.isBlank()) {
                        throw new IllegalArgumentException("Target URL has no hostname: " + raw);
                    }
                    value = host.toLowerCase(Locale.ROOT);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid target: " + raw, e);
                }
            }

            if (value.startsWith("*.")) {
                value = value.substring(2);
            }
            while (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isBlank() || value.contains("/") || value.contains("\\") || value.contains("*") || value.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid target root: " + raw);
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    @Override
    public synchronized void close() throws Exception {
        Exception first = null;
        for (Context context : contexts.values()) {
            try {
                context.database.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        contexts.clear();
        if (first != null) {
            throw first;
        }
    }

    private synchronized Workspace updateScopeInternal(
            String workspaceId,
            List<String> targetRoots,
            WorkspaceScope scope) throws Exception {
        Context context = contexts.get(workspaceId);
        if (context == null) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }
        if (context.workspace.legacy()) {
            throw new IllegalArgumentException(
                    "Legacy history is intentionally unscoped. Create a workspace to use scope isolation.");
        }

        Workspace updated = new Workspace(
                context.workspace.id(),
                context.workspace.name(),
                targetRoots,
                scope,
                context.workspace.databasePath(),
                false);
        writeWorkspaceMetadata(updated);
        context.workspace = updated;
        return updated;
    }

    private Context activeContext() {
        synchronized (this) {
            Context context = contexts.get(activeWorkspaceId);
            if (context == null) {
                throw new IllegalStateException("Active workspace is unavailable: " + activeWorkspaceId);
            }
            return context;
        }
    }

    private void loadLegacyWorkspace() throws Exception {
        Path legacyDatabase = root.resolve("history.sqlite3");
        if (!Files.exists(legacyDatabase)) {
            return;
        }
        Workspace legacy = new Workspace(
                LEGACY_ID,
                "Legacy / Unscoped",
                List.of(),
                WorkspaceScope.unscoped(),
                legacyDatabase,
                true);
        contexts.put(legacy.id(), new Context(legacy, new HistoryDatabase(legacyDatabase)));
    }

    private void loadManagedWorkspaces() throws Exception {
        try (Stream<Path> stream = Files.list(workspacesRoot)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted().toList()) {
                Path metadata = directory.resolve("workspace.properties");
                if (!Files.isRegularFile(metadata)) {
                    continue;
                }

                Properties properties = new Properties();
                try (var reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }

                String id = properties.getProperty("id", directory.getFileName().toString()).trim();
                String name = properties.getProperty("name", id).trim();
                if (id.isEmpty() || name.isEmpty() || contexts.containsKey(id)) {
                    continue;
                }

                List<String> targets = parseTargets(properties.getProperty("targets", ""));
                Path scopePath = directory.resolve(SCOPE_FILE);
                WorkspaceScope scope;
                if (Files.isRegularFile(scopePath)) {
                    WorkspaceScope imported = BurpScopeJson.importScope(scopePath);
                    boolean unscoped = Boolean.parseBoolean(
                            properties.getProperty("unscoped", "false"));
                    scope = new WorkspaceScope(
                            imported.include(),
                            imported.exclude(),
                            unscoped);
                } else {
                    scope = WorkspaceScope.fromTargetRoots(targets);
                }

                Path databasePath = directory.resolve("history.sqlite3");
                Workspace workspace = new Workspace(id, name, targets, scope, databasePath, false);
                contexts.put(id, new Context(workspace, new HistoryDatabase(databasePath)));

                // V2.0 workspaces did not have scope.json. Persist the converted
                // structured scope after successfully loading the old target roots.
                if (!Files.isRegularFile(scopePath)) {
                    writeWorkspaceMetadata(workspace);
                }
            }
        }
    }

    private Workspace createWorkspaceInternal(String name, List<String> targets) throws Exception {
        String base = slug(name);
        String id = base + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = workspacesRoot.resolve(id);
        Files.createDirectories(directory);
        Workspace workspace = new Workspace(
                id,
                name,
                targets,
                WorkspaceScope.fromTargetRoots(targets),
                directory.resolve("history.sqlite3"),
                false);
        writeWorkspaceMetadata(workspace);
        contexts.put(id, new Context(workspace, new HistoryDatabase(workspace.databasePath())));
        return workspace;
    }

    private void writeWorkspaceMetadata(Workspace workspace) throws Exception {
        Path directory = workspace.databasePath().getParent();
        Files.createDirectories(directory);

        Properties properties = new Properties();
        properties.setProperty("id", workspace.id());
        properties.setProperty("name", workspace.name());
        properties.setProperty("unscoped", Boolean.toString(workspace.scope().unscoped()));
        if (!workspace.targets().isEmpty()) {
            properties.setProperty("targets", String.join(",", workspace.targets()));
        }
        Path metadata = directory.resolve("workspace.properties");
        try (var writer = Files.newBufferedWriter(metadata, StandardCharsets.UTF_8)) {
            properties.store(writer, "Persistent HTTP History workspace");
        }

        Path scope = directory.resolve(SCOPE_FILE);
        Path tempScope = directory.resolve(SCOPE_FILE + ".tmp");
        BurpScopeJson.writeScope(tempScope, workspace.scope());
        try {
            Files.move(tempScope, scope,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(tempScope, scope, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readSavedActiveWorkspace() {
        try {
            if (!Files.isRegularFile(activeWorkspaceFile)) {
                return null;
            }
            String value = Files.readString(activeWorkspaceFile, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistActiveWorkspace() throws Exception {
        Files.writeString(activeWorkspaceFile, activeWorkspaceId + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String slug(String name) {
        String value = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.isEmpty() ? "workspace" : value;
    }

    public record CaptureResult(String workspaceId, HistoryEntry entry) {}

    private static final class Context {
        private volatile Workspace workspace;
        private final HistoryDatabase database;
        private final AtomicLong skippedOutsideTargets = new AtomicLong();

        private Context(Workspace workspace, HistoryDatabase database) {
            this.workspace = workspace;
            this.database = database;
        }
    }
}
