package io.persistenthistory;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class ScopeRule {
    private static final Pattern CIDR = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");
    private static final Pattern IPV4_RANGE = Pattern.compile(
            "^(\\d{1,3}(?:-\\d{1,3})?)\\."
                    + "(\\d{1,3}(?:-\\d{1,3})?)\\."
                    + "(\\d{1,3}(?:-\\d{1,3})?)\\."
                    + "(\\d{1,3}(?:-\\d{1,3})?)$");

    private final boolean enabled;
    private final String protocol;
    private final String host;
    private final String port;
    private final String file;
    private final HostMatcher hostMatcher;
    private final Pattern portPattern;
    private final Pattern filePattern;

    public ScopeRule(boolean enabled, String protocol, String host, String port, String file) {
        this.enabled = enabled;
        this.protocol = normalizeProtocol(protocol);
        this.host = clean(host);
        this.port = clean(port);
        this.file = clean(file);
        this.hostMatcher = compileHost(this.host);
        this.portPattern = compileRegex(this.port, 0, "port");
        this.filePattern = compileRegex(this.file, 0, "file");
    }

    public boolean enabled() {
        return enabled;
    }

    public String protocol() {
        return protocol;
    }

    public String host() {
        return host;
    }

    public String port() {
        return port;
    }

    public String file() {
        return file;
    }

    public boolean matches(String url) {
        if (!enabled || url == null || url.isBlank()) {
            return false;
        }

        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            if (!"any".equals(protocol) && !protocol.equals(scheme)) {
                return false;
            }

            String actualHost = uri.getHost();
            if (hostMatcher != null && (actualHost == null || !hostMatcher.matches(actualHost))) {
                return false;
            }

            int actualPort = uri.getPort();
            if (actualPort == -1) {
                actualPort = "https".equals(scheme) ? 443 : 80;
            }
            if (portPattern != null && !portPattern.matcher(Integer.toString(actualPort)).matches()) {
                return false;
            }

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            if (filePattern == null) {
                return true;
            }
            if (filePattern.matcher(path).matches()) {
                return true;
            }

            // Older Burp configs sometimes contain values such as "logout"
            // rather than "^/logout$". Also test the path without its leading
            // slash while still using full regex matching.
            return path.startsWith("/") && filePattern.matcher(path.substring(1)).matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static ScopeRule fromTargetRoot(String targetRoot) {
        String root = WorkspaceManager.normalizeTargets(java.util.List.of(targetRoot)).getFirst();
        return new ScopeRule(
                true,
                "any",
                "(?:^|.*\\.)" + Pattern.quote(root) + "$",
                "",
                "");
    }

    public static ScopeRule fromPrefix(boolean enabled, String prefix) {
        try {
            URI uri = URI.create(prefix);
            String scheme = uri.getScheme();
            String actualHost = uri.getHost();
            if (scheme == null
                    || actualHost == null
                    || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException(
                        "Scope prefix must be an absolute HTTP(S) URL: " + prefix);
            }

            int actualPort = uri.getPort();
            if (actualPort == -1) {
                actualPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }
            String port = Pattern.quote(Integer.toString(actualPort));
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }

            return new ScopeRule(
                    enabled,
                    scheme,
                    Pattern.quote(actualHost),
                    port,
                    "^" + Pattern.quote(path) + ".*");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Burp scope prefix: " + prefix, e);
        }
    }

    private static String normalizeProtocol(String value) {
        String protocol = clean(value).toLowerCase(Locale.ROOT);
        if (protocol.isEmpty()) {
            return "any";
        }
        if (!protocol.equals("any") && !protocol.equals("http") && !protocol.equals("https")) {
            throw new IllegalArgumentException(
                    "Protocol must be any, http, or https: " + value);
        }
        return protocol;
    }

    private static HostMatcher compileHost(String value) {
        if (value.isEmpty()) {
            return null;
        }

        Matcher cidr = CIDR.matcher(value);
        if (cidr.matches()) {
            int[] octets = new int[] {
                    parseOctet(cidr.group(1), value),
                    parseOctet(cidr.group(2), value),
                    parseOctet(cidr.group(3), value),
                    parseOctet(cidr.group(4), value)
            };
            int prefix = Integer.parseInt(cidr.group(5));
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("Invalid IPv4 CIDR prefix: " + value);
            }
            int network = ipv4ToInt(octets);
            int mask = prefix == 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
            int expected = network & mask;
            return actual -> {
                int[] parsed = parseIpv4(actual);
                return parsed != null && (ipv4ToInt(parsed) & mask) == expected;
            };
        }

        Matcher range = IPV4_RANGE.matcher(value);
        if (range.matches()) {
            int[][] bounds = new int[4][2];
            for (int i = 0; i < 4; i++) {
                String piece = range.group(i + 1);
                String[] values = piece.split("-", 2);
                int min = parseOctet(values[0], value);
                int max = values.length == 1 ? min : parseOctet(values[1], value);
                if (min > max) {
                    throw new IllegalArgumentException("Invalid IPv4 range: " + value);
                }
                bounds[i][0] = min;
                bounds[i][1] = max;
            }
            return actual -> {
                int[] parsed = parseIpv4(actual);
                if (parsed == null) return false;
                for (int i = 0; i < 4; i++) {
                    if (parsed[i] < bounds[i][0] || parsed[i] > bounds[i][1]) {
                        return false;
                    }
                }
                return true;
            };
        }

        Pattern pattern = compileRegex(value, Pattern.CASE_INSENSITIVE, "host");
        return actual -> pattern.matcher(actual).matches();
    }

    private static Pattern compileRegex(String regex, int flags, String field) {
        if (regex.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid " + field + " regex: " + regex,
                    e);
        }
    }

    private static int parseOctet(String value, String source) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 255) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid IPv4 range: " + source, e);
        }
    }

    private static int[] parseIpv4(String value) {
        if (value == null) {
            return null;
        }
        String[] pieces = value.split("\\.", -1);
        if (pieces.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(pieces[i]);
                if (octet < 0 || octet > 255) {
                    return null;
                }
                octets[i] = octet;
            }
            return octets;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int ipv4ToInt(int[] octets) {
        return (octets[0] << 24)
                | (octets[1] << 16)
                | (octets[2] << 8)
                | octets[3];
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ScopeRule that)) return false;
        return enabled == that.enabled
                && protocol.equals(that.protocol)
                && host.equals(that.host)
                && port.equals(that.port)
                && file.equals(that.file);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, protocol, host, port, file);
    }

    @Override
    public String toString() {
        return protocol + " " + host + " " + port + " " + file;
    }

    @FunctionalInterface
    private interface HostMatcher {
        boolean matches(String host);
    }
}
