package com.fluxtion.maven;

import org.apache.maven.plugin.logging.Log;
import java.time.Instant;
import java.util.Map;

/** Versioned, bounded public facts only. Never hand this writer an HTTP response or a credential. */
final class AuthRecords {
    private final Log log;
    private final String attempt = java.util.UUID.randomUUID().toString();
    String compiler;
    AuthRecords(Log log) { this.log = log; }
    void emit(String event, String identity, String source, String account, Map<String, Object> extra) {
        StringBuilder out = new StringBuilder("FLUXTION-AUTH {\"schemaVersion\":1");
        field(out, "event", event); field(out, "attemptId", attempt); field(out, "clientScope", "maven");
        field(out, "compilerOrigin", compiler); field(out, "credentialSource", source);
        field(out, "identityStatus", identity); field(out, "observedAt", Instant.now().toString());
        if (account != null && account.matches("[A-Za-z0-9_:.-]{3,128}")) field(out, "accountId", account);
        if (extra != null) for (Map.Entry<String, Object> e : extra.entrySet()) {
            if (e.getValue() instanceof Number) out.append(',').append(Json.quote(e.getKey())).append(':').append(e.getValue());
            else field(out, e.getKey(), String.valueOf(e.getValue()));
        }
        log.info(out.append('}').toString());
        System.out.flush();
    }
    private static void field(StringBuilder out, String key, String value) {
        if (value != null) out.append(',').append(Json.quote(key)).append(':').append(Json.quote(value));
    }
}
