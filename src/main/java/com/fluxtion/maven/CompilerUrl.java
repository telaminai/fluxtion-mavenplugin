package com.fluxtion.maven;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The compiler's public base URL, e.g. {@code https://compiler.example.com}. Validated so that a bearer credential is
 * only ever sent over HTTPS (plain HTTP is allowed for a loopback address, for local testing), and so that the URL
 * itself cannot smuggle a credential (no user-info, query or fragment).
 */
final class CompilerUrl {

    private final String scheme;
    private final String host;
    private final int port;

    private CompilerUrl(String scheme, String host, int port) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
    }

    static CompilerUrl parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("no compiler URL");
        }
        URI u;
        try {
            u = new URI(text.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("compiler URL is not a valid URL: " + text.trim());
        }
        String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("compiler URL must start with https:// : " + text.trim());
        }
        if (u.getRawUserInfo() != null) {
            throw new IllegalArgumentException("compiler URL must not carry user information or a credential;"
                    + " use FLUXTION_API_TOKEN or mvn fluxtion:login");
        }
        if (u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException("compiler URL must not have a query or fragment: " + u.getHost());
        }
        String path = u.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("compiler URL must be the service root (scheme, host and optional"
                    + " port), without a path: " + text.trim());
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("compiler URL has no host: " + text.trim());
        }
        if ("http".equals(scheme) && !isLoopback(host)) {
            throw new IllegalArgumentException("compiler URL must use https:// (plain http is only allowed for"
                    + " localhost): " + text.trim());
        }
        int port = u.getPort() > 0 ? u.getPort() : ("https".equals(scheme) ? 443 : 80);
        return new CompilerUrl(scheme, host, port);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || host.startsWith("127.") || "[::1]".equals(host)
                || "::1".equals(host);
    }

    /** The value for the builder's {@code fluxtion.remote.host}: scheme and host, no port, no path. */
    String builderHost() {
        return scheme + "://" + host;
    }

    /** The value for the builder's {@code fluxtion.remote.port}. */
    int builderPort() {
        return port;
    }

    /** {@code base + path}, path starting with '/'. */
    String resolve(String path) {
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
    }

    @Override
    public String toString() {
        return resolve("");
    }
}
