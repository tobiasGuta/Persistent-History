package io.persistenthistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class BurpScopeJson {
    private static final long MAX_IMPORT_BYTES = 5L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private BurpScopeJson() {}

    static WorkspaceScope importScope(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("Scope JSON is larger than 5 MiB.");
        }

        JsonNode root = JSON.readTree(path.toFile());
        JsonNode scope = locateScope(root);
        if (scope == null || !scope.isObject()) {
            throw new IllegalArgumentException("Could not find a Burp target.scope object with include/exclude rules.");
        }

        List<ScopeRule> include = parseRules(scope.path("include"));
        List<ScopeRule> exclude = parseRules(scope.path("exclude"));
        return new WorkspaceScope(include, exclude);
    }

    static void writeScope(Path path, WorkspaceScope scope) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());

        ObjectNode root = JSON.createObjectNode();
        ObjectNode target = root.putObject("target");
        ObjectNode scopeNode = target.putObject("scope");
        scopeNode.put("advanced_mode", true);
        writeRules(scopeNode.putArray("include"), scope.include());
        writeRules(scopeNode.putArray("exclude"), scope.exclude());

        JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    private static JsonNode locateScope(JsonNode root) {
        if (root == null) {
            return null;
        }

        JsonNode direct = root.path("target").path("scope");
        if (looksLikeScope(direct)) {
            return direct;
        }
        if (looksLikeScope(root)) {
            return root;
        }

        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                JsonNode found = locateScope(fields.next().getValue());
                if (found != null) {
                    return found;
                }
            }
        } else if (root.isArray()) {
            for (JsonNode child : root) {
                JsonNode found = locateScope(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean looksLikeScope(JsonNode node) {
        return node != null
                && node.isObject()
                && (node.path("include").isArray() || node.path("exclude").isArray())
                && (node.has("advanced_mode") || node.has("include") || node.has("exclude"));
    }

    private static List<ScopeRule> parseRules(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }

        List<ScopeRule> rules = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                continue;
            }
            boolean enabled = item.path("enabled").isMissingNode() || item.path("enabled").asBoolean(true);
            String prefix = text(item, "prefix");
            if (!prefix.isBlank()) {
                rules.add(ScopeRule.fromPrefix(enabled, prefix));
                continue;
            }

            String protocol = text(item, "protocol");
            if (protocol.isBlank()) {
                protocol = text(item, "scheme");
            }
            String host = text(item, "host");
            String port = text(item, "port");
            String file = text(item, "file");

            if (host.isBlank() && port.isBlank() && file.isBlank()) {
                continue;
            }
            rules.add(new ScopeRule(enabled, protocol, host, port, file));
        }
        return List.copyOf(rules);
    }

    private static void writeRules(ArrayNode array, List<ScopeRule> rules) {
        for (ScopeRule rule : rules) {
            ObjectNode item = array.addObject();
            item.put("enabled", rule.enabled());
            item.put("protocol", rule.protocol());
            if (!rule.host().isBlank()) item.put("host", rule.host());
            if (!rule.port().isBlank()) item.put("port", rule.port());
            if (!rule.file().isBlank()) item.put("file", rule.file());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
