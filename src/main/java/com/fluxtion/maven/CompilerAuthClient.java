package com.fluxtion.maven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The compiler service's account endpoints used by the plugin: device sign-in ({@code /auth/device/*}), the read-only
 * usage view ({@code GET /v1/usage}) and credential revocation ({@code DELETE /v1/credentials/{id}}).
 *
 * <p>The device code is a secret: it is held only in {@link DeviceAuthorization} (whose {@code toString} omits it) and
 * sent only in the token poll's body. Bearer credentials are sent only in the {@code Authorization} header, and
 * redirects are never followed, so a credential cannot be forwarded to another host.</p>
 */
final class CompilerAuthClient {

    static final String CLIENT_HEADER = "X-Fluxtion-Client";
    static final String CLIENT_NAME = "fluxtion-maven-plugin";
    private static final int MAX_BODY = 64 * 1024;
    private static final int SLOW_DOWN_SECONDS = 5;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Waits between polls; replaced in tests. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Why a device sign-in ended without a credential. */
    static final class DeviceFlowException extends Exception {
        enum Reason { DENIED, EXPIRED, FAILED }

        final Reason reason;

        DeviceFlowException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    static final class DeviceAuthorization {
        private final String deviceCode;
        final String userCode;
        final String verificationUri;
        final String verificationUriComplete;
        final long expiresInSeconds;
        final long intervalSeconds;

        DeviceAuthorization(String deviceCode, String userCode, String verificationUri,
                            String verificationUriComplete, long expiresInSeconds, long intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.verificationUriComplete = verificationUriComplete;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = intervalSeconds;
        }

        @Override
        public String toString() {
            return "DeviceAuthorization{userCode=" + userCode + ", verificationUri=" + verificationUri + "}";
        }
    }

    static final class Issued {
        final String accessToken;
        final String credentialId;
        final String accountId;

        Issued(String accessToken, String credentialId, String accountId) {
            this.accessToken = accessToken;
            this.credentialId = credentialId;
            this.accountId = accountId;
        }

        @Override
        public String toString() {
            return "Issued{credentialId=" + credentialId + ", accountId=" + accountId + "}";
        }
    }

    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private final CompilerUrl base;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    CompilerAuthClient(CompilerUrl base) {
        this(base, 10_000, 30_000);
    }

    CompilerAuthClient(CompilerUrl base, int connectTimeoutMillis, int readTimeoutMillis) {
        this.base = base;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** {@code POST /auth/device/authorize}: starts a device sign-in. */
    DeviceAuthorization authorize() throws IOException {
        Response r = send("POST", "/auth/device/authorize", "{}", null);
        if (r.status != 200) {
            throw new IOException("device sign-in could not start: " + describe(r));
        }
        Map<String, Object> m = parse(r);
        String deviceCode = Json.str(m, "device_code");
        String userCode = Json.str(m, "user_code");
        String uri = Json.str(m, "verification_uri");
        if (deviceCode == null || userCode == null || uri == null) {
            throw new IOException("device sign-in response is missing device_code, user_code or verification_uri");
        }
        return new DeviceAuthorization(deviceCode, printable(userCode), printable(uri),
                printable(Json.str(m, "verification_uri_complete")),
                Json.num(m, "expires_in", 600), Math.max(1, Json.num(m, "interval", 5)));
    }

    /**
     * The name this credential carries in the account's device list: "Maven on <host>", limited to what the service
     * keeps ({@code [A-Za-z0-9 ._:-]}, at most 64 characters). The service names anything else "device".
     */
    static String deviceName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "";
        }
        return deviceName(host);
    }

    static String deviceName(String host) {
        String h = host == null ? "" : host.replaceAll("[^A-Za-z0-9._:-]", "");
        String n = h.isEmpty() ? "Maven" : "Maven on " + h;
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    /**
     * Polls {@code POST /auth/device/token} until the person approves, refuses, or the code expires. Honours
     * {@code interval}, adds {@value #SLOW_DOWN_SECONDS}s on {@code slow_down}, stops at {@code expires_in}.
     */
    Issued pollForToken(DeviceAuthorization auth, Sleeper sleeper, LongSupplier clockMillis)
            throws IOException, DeviceFlowException, InterruptedException {
        long deadline = clockMillis.getAsLong() + auth.expiresInSeconds * 1000L;
        long interval = auth.intervalSeconds;
        String body = "{\"device_code\":" + Json.quote(auth.deviceCode) + ",\"device_name\":" + Json.quote(deviceName()) + "}";
        int failures = 0;
        while (true) {
            if (clockMillis.getAsLong() >= deadline) {
                throw expired();
            }
            sleeper.sleep(interval * 1000L);
            if (clockMillis.getAsLong() >= deadline) {
                throw expired();
            }
            Response r;
            try {
                r = send("POST", "/auth/device/token", body, null);
            } catch (IOException e) {
                if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                    throw new IOException("could not reach the compiler service while waiting for sign-in: "
                            + e.getMessage(), e);
                }
                continue;
            }
            if (r.status == 200) {
                Map<String, Object> m = parse(r);
                String token = Json.str(m, "access_token");
                if (token == null || token.trim().isEmpty()) {
                    throw new DeviceFlowException(DeviceFlowException.Reason.FAILED,
                            "sign-in completed but the service returned no credential");
                }
                return new Issued(token.trim(), Json.str(m, "credential_id"), Json.str(m, "account_id"));
            }
            if (r.status == 429 || r.status >= 500) {
                if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                    throw new IOException("the compiler service is not answering sign-in polls: " + describe(r));
                }
                interval += SLOW_DOWN_SECONDS;
                continue;
            }
            failures = 0;
            String error = errorCode(r);
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += SLOW_DOWN_SECONDS;
                continue;
            }
            if ("access_denied".equals(error)) {
                throw new DeviceFlowException(DeviceFlowException.Reason.DENIED,
                        "sign-in was refused in the browser; run mvn fluxtion:login again to retry");
            }
            if ("expired_token".equals(error)) {
                throw expired();
            }
            throw new DeviceFlowException(DeviceFlowException.Reason.FAILED,
                    "sign-in failed: HTTP " + r.status + "; run mvn fluxtion:login again to retry");
        }
    }

    /** {@code GET /v1/usage}: the account's plan and allowance, as returned. */
    Map<String, Object> usage(String token) throws IOException {
        Response r = send("GET", "/v1/usage", null, token);
        if (r.status != 200) {
            throw new IOException("usage unavailable: " + describe(r));
        }
        return parse(r);
    }

    /** {@code DELETE /v1/credentials/{id}}: returns the HTTP status (204 revoked, 404 already gone). */
    int revoke(String credentialId, String token) throws IOException {
        String id = URLEncoder.encode(credentialId, "UTF-8").replace("+", "%20");
        return send("DELETE", "/v1/credentials/" + id, null, token).status;
    }

    private static DeviceFlowException expired() {
        return new DeviceFlowException(DeviceFlowException.Reason.EXPIRED,
                "the sign-in code expired before it was approved; run mvn fluxtion:login again");
    }

    private static Map<String, Object> parse(Response r) throws IOException {
        try {
            return Json.object(r.body);
        } catch (IllegalArgumentException e) {
            throw new IOException("the compiler service returned an unreadable response (HTTP " + r.status + ")");
        }
    }

    private static String errorCode(Response r) {
        try {
            return Json.str(Json.object(r.body), "error");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code text} with control characters replaced, so a server-supplied string printed to the terminal cannot
     * carry escape sequences (rewriting earlier lines, hiding text, changing the title). Null stays null.
     */
    static String printable(String text) {
        if (text == null) return null;
        StringBuilder b = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            b.append(Character.isISOControl(c) || c == '\u202e' || c == '\u202d' ? '?' : c);
        }
        return b.toString();
    }

    /**
     * The address to hand to the system browser, or null when it is not an https page (or http on loopback): a
     * server-supplied {@code file:} or custom-scheme URI must never be opened by the build.
     */
    static java.net.URI browsable(String uri) {
        if (uri == null) return null;
        try {
            java.net.URI u = new java.net.URI(uri);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = u.getHost();
            if (host == null || u.getRawUserInfo() != null) return null;
            if ("https".equals(scheme) || ("http".equals(scheme) && CompilerUrl.isLoopback(host))) return u;
            return null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Do not echo server error fields: even a single field can contain a reflected credential. */
    static String describe(Response r) {
        return "HTTP " + r.status;
    }

    Response send(String method, String path, String jsonBody, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base.resolve(path)).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(connectTimeoutMillis);
            c.setReadTimeout(readTimeoutMillis);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty(CLIENT_HEADER, CLIENT_NAME);
            if (bearer != null) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(status, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (out.size() + n > MAX_BODY) {
                    throw new IOException("response too large");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
