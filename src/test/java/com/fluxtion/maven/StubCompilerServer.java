package com.fluxtion.maven;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** A stand-in for the compiler service's account endpoints, on a loopback port. */
final class StubCompilerServer implements AutoCloseable {

    static final String TOKEN = "fxk_cred1.s3cr3t-value";
    static final String DEVICE_CODE = "dev-code-SECRET-123";

    static final class Request {
        final String method;
        final String path;
        final String authorization;
        final String clientHeader;
        final String body;

        Request(String method, String path, String authorization, String clientHeader, String body) {
            this.method = method;
            this.path = path;
            this.authorization = authorization;
            this.clientHeader = clientHeader;
            this.body = body;
        }
    }

    private final HttpServer server;
    final List<Request> requests = Collections.synchronizedList(new ArrayList<Request>());
    /** Responses for successive token polls: "status body". The last one repeats. */
    final Deque<String[]> tokenReplies = new ArrayDeque<>();
    int revokeStatus = 204;
    /** What {@code GET /v1/usage} answers: by default a compiler from before credit kinds and plans. */
    volatile String usageBody = OLD_USAGE;

    static final String OLD_USAGE = "{\"accountId\":\"acct-42\",\"plan\":\"free\",\"active\":true,\"available\":250,"
            + "\"reserved\":0,\"limits\":{\"a\":[1,2.5,null]}}";
    /** A compiler with credit kinds and plans (fluxtion-compiler PR #121). */
    static final String PLANS_USAGE = "{\"accountId\":\"acct-42\",\"plan\":\"free\",\"active\":true,\"available\":250,"
            + "\"reserved\":0,\"consumed\":5,\"released\":0,\"generations\":5,\"failures\":0,"
            + "\"kinds\":{\"compile\":{\"available\":250,\"reserved\":0,\"consumed\":5,\"released\":0,\"settled\":5,\"failures\":0},"
            + "\"interpret\":{\"available\":40,\"reserved\":1,\"consumed\":9,\"released\":0,\"settled\":9,\"failures\":0}},"
            + "\"currentPeriod\":\"2026-10\",\"nextReset\":null,\"buckets\":[],"
            + "\"capabilities\":{\"plan\":\"free\",\"maxNodes\":20,\"maxInputs\":6,"
            + "\"maxExportedInterfaces\":1,\"maxEventTypes\":6,\"targets\":[\"java\"],"
            + "\"wasm\":false,\"assurance\":\"none\",\"compilePerMinute\":10,\"interpretPerMinute\":30},"
            + "\"policyVersion\":\"p1\",\"as_of_sequence\":12}";
    long expiresIn = 600;
    long interval = 5;
    String userCode = "WDJB-MJHT";

    StubCompilerServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void tokenReply(int status, String body) {
        tokenReplies.add(new String[]{String.valueOf(status), body});
    }

    static String success() {
        return "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"credential_id\":\"cred1\","
                + "\"account_id\":\"acct-42\"}";
    }

    List<Request> requests(String method, String path) {
        List<Request> out = new ArrayList<>();
        synchronized (requests) {
            for (Request r : requests) {
                if (r.method.equals(method) && r.path.equals(path)) out.add(r);
            }
        }
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = read(ex.getRequestBody());
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(new Request(method, path, ex.getRequestHeaders().getFirst("Authorization"),
                ex.getRequestHeaders().getFirst(CompilerAuthClient.CLIENT_HEADER), body));
        if ("POST".equals(method) && "/auth/device/authorize".equals(path)) {
            reply(ex, 200, "{\"device_code\":\"" + DEVICE_CODE + "\",\"user_code\":" + Json.quote(userCode) + ","
                    + "\"verification_uri\":\"https://login.example.com/device\","
                    + "\"verification_uri_complete\":\"https://login.example.com/device?user_code=WDJB-MJHT\","
                    + "\"expires_in\":" + expiresIn + ",\"interval\":" + interval + "}");
        } else if ("POST".equals(method) && "/auth/device/token".equals(path)) {
            String[] r;
            synchronized (tokenReplies) {
                r = tokenReplies.size() > 1 ? tokenReplies.poll() : tokenReplies.peek();
            }
            if (r == null) r = new String[]{"400", "{\"error\":\"authorization_pending\"}"};
            reply(ex, Integer.parseInt(r[0]), r[1]);
        } else if ("GET".equals(method) && "/v1/usage".equals(path)) {
            if (("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                reply(ex, 200, usageBody);
            } else {
                reply(ex, 401, "{\"error\":\"AUTH_REQUIRED\",\"message\":\"sign in to continue\"}");
            }
        } else if ("DELETE".equals(method) && path.startsWith("/v1/credentials/")) {
            reply(ex, revokeStatus, "");
        } else {
            reply(ex, 404, "{\"error\":\"NOT_FOUND\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (status == 204 || bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
