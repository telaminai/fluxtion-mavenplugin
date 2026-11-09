package com.fluxtion.maven;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AuthRecordsTest {
    private FluxtionLoginMojo mojo(StubCompilerServer s, CredentialStore store, RecordingLog log) {
        FluxtionLoginMojo m = new FluxtionLoginMojo();
        m.env = key -> null; m.compilerUrl = s.url(); m.credentialStore = store;
        m.verificationOrigin = "https://login.example.com"; m.browser = false; m.setLog(log);
        long[] time = {0}; m.clockMillis = () -> time[0]; m.sleeper = ms -> time[0] += ms;
        return m;
    }
    private List<Map<String,Object>> records(RecordingLog log) throws Exception {
        List<Map<String,Object>> result = new ArrayList<>();
        for (String line : log.lines) if (line.contains("FLUXTION-AUTH "))
            result.add(Json.object(line.substring(line.indexOf("FLUXTION-AUTH ") + 14)));
        assertFalse(log.all().contains(StubCompilerServer.TOKEN));
        assertFalse(log.all().contains(StubCompilerServer.DEVICE_CODE));
        return result;
    }
    @Test public void pendingAndVerifiedTerminalShareAttemptAndAlreadySignedInIsVerified() throws Exception {
        try (StubCompilerServer s = new StubCompilerServer()) {
            Path dir = Files.createTempDirectory("auth-records");
            CredentialStore store = new CredentialStore(dir.resolve("credentials"));
            RecordingLog log = new RecordingLog(); FluxtionLoginMojo m = mojo(s,store,log);
            s.tokenReply(200,StubCompilerServer.success()); m.execute();
            List<Map<String,Object>> r = records(log);
            assertEquals(2,r.size()); assertEquals("pending",r.get(0).get("event"));
            assertEquals("signed_in",r.get(1).get("event"));
            assertEquals(r.get(0).get("attemptId"),r.get(1).get("attemptId"));
            assertEquals("verified",r.get(1).get("identityStatus"));
            assertEquals("acct-42",r.get(1).get("accountId"));
            int authorize = s.requests("POST","/auth/device/authorize").size();
            log.lines.clear(); m.execute(); r = records(log);
            assertEquals(1,r.size()); assertEquals("signed_in",r.get(0).get("event"));
            assertEquals(authorize,s.requests("POST","/auth/device/authorize").size());
            s.meStatus = 503; log.lines.clear();
            assertThrows(org.apache.maven.plugin.MojoExecutionException.class,m::execute);
            assertEquals("unknown",records(log).get(0).get("identityStatus"));
            assertEquals(authorize,s.requests("POST","/auth/device/authorize").size());
            Files.delete(store.file()); Files.deleteIfExists(dir.resolve("credentials.lock")); Files.deleteIfExists(dir.resolve("credential.lock")); Files.delete(dir);
        }
    }
    @Test public void deniedExpiredAndCancelledHaveCorrelatedTerminalRecords() throws Exception {
        for (String outcome : Arrays.asList("denied","expired","cancelled")) {
            try (StubCompilerServer s = new StubCompilerServer()) {
                Path dir = Files.createTempDirectory("auth-outcome");
                CredentialStore store = new CredentialStore(dir.resolve("credentials"));
                RecordingLog log = new RecordingLog(); FluxtionLoginMojo m = mojo(s,store,log);
                if (outcome.equals("cancelled")) m.sleeper = ms -> { throw new InterruptedException(); };
                else s.tokenReply(400,"{\"error\":\"" + (outcome.equals("denied") ? "access_denied" : "expired_token") + "\"}");
                try { assertThrows(org.apache.maven.plugin.MojoFailureException.class,m::execute); }
                finally { Thread.interrupted(); }
                List<Map<String,Object>> r = records(log);
                assertEquals(2,r.size()); assertEquals(outcome,r.get(1).get("event"));
                assertEquals(r.get(0).get("attemptId"),r.get(1).get("attemptId"));
                assertFalse(store.exists()); Files.deleteIfExists(dir.resolve("credentials.lock")); Files.deleteIfExists(dir.resolve("credential.lock")); Files.delete(dir);
            }
        }
    }
    @Test public void envOverrideAndUntrustedOriginNeverStartPolling() throws Exception {
        try (StubCompilerServer s = new StubCompilerServer()) {
            Path dir = Files.createTempDirectory("auth-refusal");
            CredentialStore store = new CredentialStore(dir.resolve("credentials"));
            RecordingLog log = new RecordingLog(); FluxtionLoginMojo m = mojo(s,store,log);
            m.env = k -> k.equals("FLUXTION_API_TOKEN") ? StubCompilerServer.TOKEN : null;
            assertThrows(org.apache.maven.plugin.MojoFailureException.class,m::execute);
            assertEquals("usage_error",records(log).get(0).get("event")); assertTrue(s.requests.isEmpty());
            m.env = k -> null; m.verificationOrigin = "https://trusted.example"; log.lines.clear();
            assertThrows(org.apache.maven.plugin.MojoExecutionException.class,m::execute);
            assertEquals("service_error",records(log).get(0).get("event"));
            assertFalse(log.all().contains(s.userCode)); assertEquals(0,s.requests("POST","/auth/device/token").size());
            Files.deleteIfExists(dir.resolve("credentials.lock")); Files.deleteIfExists(dir.resolve("credential.lock")); Files.delete(dir);
        }
    }
    @Test public void oversizedPollIntervalCannotOutliveExpiry() throws Exception {
        try (StubCompilerServer s = new StubCompilerServer()) {
            CompilerAuthClient c = new CompilerAuthClient(CompilerUrl.parse(s.url()));
            long[] now = {0};
            CompilerAuthClient.DeviceAuthorization a = new CompilerAuthClient.DeviceAuthorization(
                "synthetic", "DEMO", "https://login.example.com", null, 30, Long.MAX_VALUE);
            assertThrows(CompilerAuthClient.DeviceFlowException.class,
                () -> c.pollForToken(a, ms -> { assertTrue(ms <= 30000); now[0] += ms; }, () -> now[0]));
            assertEquals(30000,now[0]); assertTrue(s.requests.isEmpty());
        }
    }
    @Test public void originValidationRejectsLookalikesUserInfoAndFragments() throws Exception {
        VerificationOrigin.check("https://login.example.com/device?user_code=A","https://login.example.com");
        for (String uri : Arrays.asList("http://login.example.com/device", "https://login.example.com.evil.test/device",
                "https://evil.test@login.example.com/device", "https://login.example.com/device#x", "https://login.example.com:444/device"))
            assertThrows(java.io.IOException.class, () -> VerificationOrigin.check(uri,"https://login.example.com"));
    }
    @Test public void stagingAndProductionPinsAreExact() throws Exception {
        String staging = VerificationOrigin.trusted("https://compiler-staging.fluxtion-playground.dev", null);
        assertEquals("https://meticulous-comfort-69-staging.authkit.app", staging);
        VerificationOrigin.check(staging + "/activate?user_code=DEMO", staging);
        assertThrows(java.io.IOException.class, () -> VerificationOrigin.check("https://different.authkit.app/activate", staging));
        String production = VerificationOrigin.trusted("https://compiler.fluxtion-playground.dev", null);
        assertEquals("https://restful-nature-87.authkit.app", production);
        for (String field : Arrays.asList("/device", "/device?user_code=GOOD-1234")) {
            VerificationOrigin.check(production + field, production);
            assertThrows(java.io.IOException.class, () -> VerificationOrigin.check(staging + field, production));
            assertThrows(java.io.IOException.class, () -> VerificationOrigin.check(production + ".evil.test" + field, production));
        }
        assertNull(VerificationOrigin.trusted("https://compiler.fluxtion-playground.dev.evil.test", null));
        assertThrows(java.io.IOException.class, () -> VerificationOrigin.check(staging, null));
    }

    @Test public void untrustedCompleteAddressIsNeverPublishedOrPolled() throws Exception {
        try (StubCompilerServer s = new StubCompilerServer()) {
            Path dir=Files.createTempDirectory("complete-origin");
            try {
                CredentialStore store=new CredentialStore(dir.resolve("credentials"));
                RecordingLog log=new RecordingLog(); FluxtionLoginMojo m=mojo(s,store,log);
                s.verificationUriComplete="https://attacker.example/device?user_code=DEMO";
                s.tokenReply(200,StubCompilerServer.success());
                assertThrows(org.apache.maven.plugin.MojoExecutionException.class,m::execute);
                assertEquals(0,s.requests("POST","/auth/device/token").size());
                assertFalse(log.all().contains("attacker.example"));
                assertFalse(log.all().contains(s.userCode));
                assertFalse(log.all().contains("\"event\":\"pending\""));
                assertFalse(Files.exists(store.file()));
            } finally {
                try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
            }
        }
    }

    @Test public void invalidUserCodesAreNeverPrintedOrPolled() throws Exception {
        for (String code : Arrays.asList("", "A\nB", "A\u001b[31m", "<script>", "é", "A".repeat(33))) {
            try (StubCompilerServer s = new StubCompilerServer()) {
                Path dir = Files.createTempDirectory("invalid-user-code");
                try {
                    CredentialStore store = new CredentialStore(dir.resolve("credentials"));
                    RecordingLog log = new RecordingLog();
                    s.userCode = code;
                    s.tokenReply(200, StubCompilerServer.success());
                    assertThrows(org.apache.maven.plugin.MojoExecutionException.class, () -> mojo(s, store, log).execute());
                    assertEquals(0, s.requests("POST", "/auth/device/token").size());
                    assertFalse(log.all().contains("\"event\":\"pending\""));
                    if (!code.isEmpty()) assertFalse(log.all().contains(code));
                    assertFalse(store.exists());
                } finally {
                    try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                        paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch(Exception ignored) {} });
                    }
                }
            }
        }
    }

}
