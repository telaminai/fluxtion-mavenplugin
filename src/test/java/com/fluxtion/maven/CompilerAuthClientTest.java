package com.fluxtion.maven;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CompilerAuthClientTest {

    private StubCompilerServer server;
    private CompilerAuthClient client;
    private final long[] now = {1_000_000L};
    private final List<Long> sleeps = new ArrayList<>();
    private final CompilerAuthClient.Sleeper sleeper = millis -> {
        sleeps.add(millis);
        now[0] += millis;
    };

    @Before
    public void setUp() throws Exception {
        server = new StubCompilerServer();
        client = new CompilerAuthClient(CompilerUrl.parse(server.url()));
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void pendingThenSlowDownThenSuccess() throws Exception {
        server.tokenReply(400, "{\"error\":\"authorization_pending\"}");
        server.tokenReply(400, "{\"error\":\"slow_down\"}");
        server.tokenReply(200, StubCompilerServer.success());

        CompilerAuthClient.DeviceAuthorization auth = client.authorize();
        Assert.assertEquals("WDJB-MJHT", auth.userCode);
        Assert.assertFalse("the device code is a secret", auth.toString().contains(StubCompilerServer.DEVICE_CODE));

        CompilerAuthClient.Issued issued = client.pollForToken(auth, sleeper, () -> now[0]);
        Assert.assertEquals(StubCompilerServer.TOKEN, issued.accessToken);
        Assert.assertEquals("cred1", issued.credentialId);
        Assert.assertEquals("acct-42", issued.accountId);
        Assert.assertFalse(issued.toString().contains(StubCompilerServer.TOKEN));
        // interval honoured, and slow_down adds 5 seconds to every later wait
        Assert.assertEquals(Arrays.asList(5_000L, 5_000L, 10_000L), sleeps);

        List<StubCompilerServer.Request> polls = server.requests("POST", "/auth/device/token");
        Assert.assertEquals(3, polls.size());
        for (StubCompilerServer.Request r : polls) {
            Assert.assertEquals("{\"device_code\":\"" + StubCompilerServer.DEVICE_CODE + "\"}", r.body);
            Assert.assertNull("no credential is sent while polling", r.authorization);
            Assert.assertEquals(CompilerAuthClient.CLIENT_NAME, r.clientHeader);
        }
        Assert.assertEquals("{}", server.requests("POST", "/auth/device/authorize").get(0).body);
    }

    @Test
    public void accessDeniedEndsTheAttempt() throws Exception {
        server.tokenReply(400, "{\"error\":\"authorization_pending\"}");
        server.tokenReply(400, "{\"error\":\"access_denied\"}");
        try {
            client.pollForToken(client.authorize(), sleeper, () -> now[0]);
            Assert.fail("expected a refusal");
        } catch (CompilerAuthClient.DeviceFlowException e) {
            Assert.assertEquals(CompilerAuthClient.DeviceFlowException.Reason.DENIED, e.reason);
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("refused"));
            Assert.assertFalse(e.getMessage().contains(StubCompilerServer.DEVICE_CODE));
        }
    }

    @Test
    public void expiredTokenEndsTheAttempt() throws Exception {
        server.tokenReply(400, "{\"error\":\"expired_token\"}");
        try {
            client.pollForToken(client.authorize(), sleeper, () -> now[0]);
            Assert.fail("expected expiry");
        } catch (CompilerAuthClient.DeviceFlowException e) {
            Assert.assertEquals(CompilerAuthClient.DeviceFlowException.Reason.EXPIRED, e.reason);
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("expired"));
        }
    }

    @Test
    public void stopsAtExpiresInWithoutAnAnswer() throws Exception {
        server.expiresIn = 20;
        server.interval = 5;
        server.tokenReply(400, "{\"error\":\"authorization_pending\"}");
        try {
            client.pollForToken(client.authorize(), sleeper, () -> now[0]);
            Assert.fail("expected expiry");
        } catch (CompilerAuthClient.DeviceFlowException e) {
            Assert.assertEquals(CompilerAuthClient.DeviceFlowException.Reason.EXPIRED, e.reason);
        }
        // polls at 5, 10, 15 seconds; the wait to 20 reaches expires_in, so no fourth poll
        Assert.assertEquals(3, server.requests("POST", "/auth/device/token").size());
    }

    @Test
    public void usageSendsTheBearerCredential() throws Exception {
        Map<String, Object> usage = client.usage(StubCompilerServer.TOKEN);
        Assert.assertEquals("free", usage.get("plan"));
        Assert.assertEquals(250L, usage.get("available"));
        Assert.assertEquals("Bearer " + StubCompilerServer.TOKEN,
                server.requests("GET", "/v1/usage").get(0).authorization);
    }

    @Test
    public void revokeDeletesTheCredential() throws Exception {
        Assert.assertEquals(204, client.revoke("cred1", StubCompilerServer.TOKEN));
        StubCompilerServer.Request r = server.requests("DELETE", "/v1/credentials/cred1").get(0);
        Assert.assertEquals("Bearer " + StubCompilerServer.TOKEN, r.authorization);
    }

    @Test
    public void compilerUrlRefusesCredentialsAndPlainHttp() {
        for (String bad : new String[]{"https://user:pw@compiler.example.com", "http://compiler.example.com",
                "https://compiler.example.com/?token=x", "ftp://compiler.example.com",
                "https://compiler.example.com/api"}) {
            try {
                CompilerUrl.parse(bad);
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                Assert.assertFalse(expected.getMessage().contains("pw@"));
            }
        }
        CompilerUrl u = CompilerUrl.parse("https://compiler.example.com");
        Assert.assertEquals("https://compiler.example.com", u.builderHost());
        Assert.assertEquals(443, u.builderPort());
        Assert.assertEquals("https://compiler.example.com/v1/usage", u.resolve("/v1/usage"));
        CompilerUrl local = CompilerUrl.parse("http://127.0.0.1:8080/");
        Assert.assertEquals("http://127.0.0.1", local.builderHost());
        Assert.assertEquals(8080, local.builderPort());
    }
}
