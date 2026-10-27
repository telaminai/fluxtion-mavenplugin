package com.fluxtion.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

public class LoginLogoutMojoTest {

    private StubCompilerServer server;
    private Path home;
    private CredentialStore store;
    private final Map<String, String> env = new HashMap<>();
    private RecordingLog log;
    private final long[] now = {0L};

    @Before
    public void setUp() throws Exception {
        server = new StubCompilerServer();
        home = Files.createTempDirectory("fluxtion-login-test");
        store = new CredentialStore(home.resolve(".fluxtion/credentials"));
        log = new RecordingLog();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
        try (java.util.stream.Stream<Path> s = Files.walk(home)) {
            s.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private FluxtionLoginMojo login() {
        FluxtionLoginMojo m = new FluxtionLoginMojo();
        m.env = env::get;
        m.credentialStore = store;
        m.compilerUrl = server.url();
        m.browser = false;
        m.sleeper = millis -> now[0] += millis;
        m.clockMillis = () -> now[0];
        m.setLog(log);
        return m;
    }

    private FluxtionLogoutMojo logout() {
        FluxtionLogoutMojo m = new FluxtionLogoutMojo();
        m.env = env::get;
        m.credentialStore = store;
        m.setLog(log);
        return m;
    }

    @Test
    public void loginPrintsThePlanAndTheCompileAndInterpretCredits() throws Exception {
        server.usageBody = StubCompilerServer.PLANS_USAGE;
        server.tokenReply(200, StubCompilerServer.success());
        login().execute();
        String out = log.all();
        Assert.assertTrue(out, out.contains("Plan: Free; compile credits available: 250; interpret credits available: 40."));
        Assert.assertTrue(out, out.contains("Plan limits: 20 nodes, 6 inputs (event types + exported methods), 1 exported interface,"
                + " targets Java."));
    }

    @Test
    public void loginAgainstAnOlderCompilerPrintsTheCompileCreditsOnly() throws Exception {
        server.tokenReply(200, StubCompilerServer.success());
        login().execute();
        String out = log.all();
        Assert.assertTrue(out, out.contains("Plan: Free; compile credits available: 250."));
        Assert.assertFalse(out, out.contains("interpret"));
        Assert.assertFalse(out, out.contains("Plan limits"));
    }

    @Test
    public void loginStoresAProtectedCredential() throws Exception {
        server.tokenReply(400, "{\"error\":\"authorization_pending\"}");
        server.tokenReply(200, StubCompilerServer.success());
        login().execute();

        CredentialStore.Stored stored = store.read();
        Assert.assertEquals(StubCompilerServer.TOKEN, stored.token);
        Assert.assertEquals("cred1", stored.credentialId);
        Assert.assertEquals("acct-42", stored.accountId);
        Assert.assertEquals(server.url(), stored.url);
        if (store.file().getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Assert.assertEquals("rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file())));
            Assert.assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(store.file().getParent())));
        }
        String out = log.all();
        Assert.assertTrue(out, out.contains("WDJB-MJHT"));
        Assert.assertTrue(out, out.contains("https://login.example.com/device"));
        Assert.assertTrue(out, out.contains("acct-42"));
        Assert.assertTrue(out, out.contains("250"));
        Assert.assertFalse("credential printed", out.contains(StubCompilerServer.TOKEN));
        Assert.assertFalse("device code printed", out.contains(StubCompilerServer.DEVICE_CODE));
        try (java.util.stream.Stream<Path> files = Files.list(store.file().getParent())) {
            Assert.assertEquals("only the credential is left behind", 1, files.count());
        }
    }

    @Test
    public void loginRefusesInCi() throws Exception {
        env.put("CI", "true");
        try {
            login().execute();
            Assert.fail("expected refusal in CI");
        } catch (MojoFailureException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("FLUXTION_API_TOKEN"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("administrator"));
        }
        Assert.assertTrue("no request in CI", server.requests.isEmpty());
        Assert.assertFalse(store.exists());
    }

    @Test
    public void loginRefusesOnCiSystemsThatDoNotSetCi() throws Exception {
        env.put("JENKINS_URL", "https://jenkins.example.com/");
        try {
            login().execute();
            Assert.fail("expected refusal in CI");
        } catch (MojoFailureException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("FLUXTION_API_TOKEN"));
        }
        Assert.assertTrue("no request in CI", server.requests.isEmpty());
        Assert.assertFalse(store.exists());
    }

    @Test
    public void logoutRevokesOnlyAtTheIssuingService() throws Exception {
        try (StubCompilerServer elsewhere = new StubCompilerServer()) {
            store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
            FluxtionLogoutMojo m = logout();
            m.compilerUrl = elsewhere.url();
            m.execute();
            Assert.assertTrue("the credential went to a service that did not issue it", elsewhere.requests.isEmpty());
            Assert.assertEquals(1, server.requests("DELETE", "/v1/credentials/cred1").size());
            Assert.assertFalse(store.exists());
        }
    }

    @Test
    public void loginDeniedStoresNothing() throws Exception {
        server.tokenReply(400, "{\"error\":\"access_denied\"}");
        try {
            login().execute();
            Assert.fail("expected refusal");
        } catch (MojoFailureException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("refused"));
        }
        Assert.assertFalse(store.exists());
    }

    @Test
    public void logoutRevokesAndDeletes() throws Exception {
        store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
        logout().execute();
        Assert.assertEquals(1, server.requests("DELETE", "/v1/credentials/cred1").size());
        Assert.assertEquals("Bearer " + StubCompilerServer.TOKEN,
                server.requests("DELETE", "/v1/credentials/cred1").get(0).authorization);
        Assert.assertFalse(store.exists());
        Assert.assertTrue(log.all(), log.all().contains("Revoked credential cred1"));
        Assert.assertFalse(log.all().contains(StubCompilerServer.TOKEN));
    }

    @Test
    public void logoutDeletesEvenWhenRevocationFails() throws Exception {
        server.revokeStatus = 503;
        store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
        logout().execute();
        Assert.assertFalse(store.exists());
        Assert.assertTrue(log.all(), log.all().contains("Could not revoke credential cred1"));
    }

    @Test
    public void a401OnRevokeIsNeverReportedAsRevoked() throws Exception {
        server.revokeStatus = 401;
        store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
        logout().execute();
        Assert.assertFalse(store.exists());
        Assert.assertFalse(log.all(), log.all().contains("already revoked or expired"));
        Assert.assertTrue(log.all(), log.all().contains("revoke it from the account's credentials list"));
    }

    @Test
    public void logoutWhenUnreachableStillDeletes() throws Exception {
        String url = server.url();
        server.close();
        store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", url));
        logout().execute();
        Assert.assertFalse(store.exists());
        Assert.assertTrue(log.all(), log.all().contains("Could not revoke"));
    }

    @Test
    public void storedFileFormatHoldsNoSurprises() throws Exception {
        store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
        String text = new String(Files.readAllBytes(store.file()), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("token="));
        Assert.assertTrue(text.contains("Do not commit"));
    }
}
