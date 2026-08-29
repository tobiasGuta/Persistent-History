package io.persistenthistory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

record RequestTarget(String host, int port, boolean secure) {
    static Optional<RequestTarget> fromUrl(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean secure;
            if ("https".equalsIgnoreCase(scheme)) {
                secure = true;
            } else if ("http".equalsIgnoreCase(scheme)) {
                secure = false;
            } else {
                return Optional.empty();
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return Optional.empty();
            }

            int port = uri.getPort();
            if (port == -1) {
                port = secure ? 443 : 80;
            }
            if (port < 1 || port > 65535) {
                return Optional.empty();
            }

            return Optional.of(new RequestTarget(host, port, secure));
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
