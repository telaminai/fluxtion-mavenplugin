package com.fluxtion.maven;

import org.junit.Test;
import org.junit.Assert;
import org.apache.maven.plugin.MojoExecutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class FluxtionAccountMojoTest {
    @Test public void environmentWinsAndAccountComesFromServerNotSavedMetadata() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            Path file = Files.createTempDirectory("account-test").resolve("credentials");
            CredentialStore store = new CredentialStore(file);
            store.write(new CredentialStore.Stored("bad-saved-token", "old", "wrong-account", server.url()));
            RecordingLog log = new RecordingLog();
            FluxtionAccountMojo m = mojo(server, store, log);
            Map<String,String> env = new HashMap<>(); env.put("FLUXTION_API_TOKEN", StubCompilerServer.TOKEN);
            m.env = env::get; server.usageBody = StubCompilerServer.PLANS_USAGE;
            m.execute();
            Assert.assertTrue(log.all(), log.all().contains("Account ID (server verified): acct-42"));
            Assert.assertTrue(log.all(), log.all().contains("6 inputs (event types + exported methods)"));
            Assert.assertFalse(log.all().contains(StubCompilerServer.TOKEN));
            Assert.assertFalse(log.all().contains("bad-saved-token"));
            Assert.assertFalse(log.all().contains("wrong-account"));
            Assert.assertEquals(2, server.requests.size());
            Assert.assertTrue(server.requests.stream().allMatch(r -> r.method.equals("GET")));
            Assert.assertEquals("bad-saved-token", store.read().token);
            Files.delete(file); Files.delete(file.getParent());
        }
    }
    @Test public void storedLoginWorksAndMissingUsageScopeRetainsVerifiedIdentity() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            Path file = Files.createTempDirectory("account-test").resolve("credentials");
            CredentialStore store = new CredentialStore(file);
            store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "stale-account", server.url()));
            RecordingLog log = new RecordingLog();
            FluxtionAccountMojo m = mojo(server, store, log);
            m.execute();
            Assert.assertTrue(log.all().contains("saved Maven sign-in"));
            server.usageStatus = 403; server.usageBody = "{\"error\":\"SCOPE_REQUIRED\"}";
            Assert.assertThrows(MojoExecutionException.class, m::execute);
            Assert.assertTrue(log.all().contains("Identity verified, but limits unavailable"));
            Assert.assertFalse(log.all().contains(StubCompilerServer.TOKEN));
            Files.delete(file); Files.delete(file.getParent());
        }
    }
    @Test public void signedOutMakesNoRequestsAndRevokedCredentialIsNotAnAccount() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            Path file = Files.createTempDirectory("account-test").resolve("credentials");
            CredentialStore store = new CredentialStore(file); RecordingLog log = new RecordingLog();
            FluxtionAccountMojo m = mojo(server, store, log);
            Assert.assertThrows(MojoExecutionException.class, m::execute);
            Assert.assertEquals(0, server.requests.size());
            store.write(new CredentialStore.Stored("revoked", "old", "stale-account", server.url()));
            Assert.assertThrows(MojoExecutionException.class, m::execute);
            Assert.assertFalse(log.all().contains("server verified"));
            Assert.assertEquals(1, server.requests.size());
            Files.delete(file); Files.delete(file.getParent());
        }
    }
    @Test public void mismatchedUsageIsNotPrintedAndSessionModeDoesNotQueryTokenAccount() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            Path file = Files.createTempDirectory("account-test").resolve("credentials");
            CredentialStore store = new CredentialStore(file);
            store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN, "cred1", "acct-42", server.url()));
            RecordingLog log = new RecordingLog(); FluxtionAccountMojo m = mojo(server, store, log);
            server.usageBody = "{\"accountId\":\"other-account\",\"plan\":\"wrong-plan\"}";
            Assert.assertThrows(MojoExecutionException.class, m::execute);
            Assert.assertFalse(log.all().contains("wrong-plan"));
            int requests = server.requests.size();
            String previous = System.getProperty("fluxtion.remote.auth");
            try {
                System.setProperty("fluxtion.remote.auth", "session");
                Assert.assertThrows(MojoExecutionException.class, m::execute);
                Assert.assertEquals(requests, server.requests.size());
            } finally {
                if (previous == null) System.clearProperty("fluxtion.remote.auth");
                else System.setProperty("fluxtion.remote.auth", previous);
            }
            Files.delete(file); Files.delete(file.getParent());
        }
    }

    @Test public void executionSpecificServiceStopsBeforeAnyClientIsCreated() throws Exception {
        FluxtionAccountMojo m = new FluxtionAccountMojo();
        m.env = key -> "FLUXTION_API_TOKEN".equals(key) ? "synthetic" : null;
        m.clientFactory = url -> { throw new AssertionError("must not create a client"); };
        m.project = new org.apache.maven.project.MavenProject();
        org.apache.maven.model.Plugin plugin = new org.apache.maven.model.Plugin();
        plugin.setGroupId("com.telamin.fluxtion"); plugin.setArtifactId("fluxtion-maven-plugin");
        org.apache.maven.model.PluginExecution execution = new org.apache.maven.model.PluginExecution();
        org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom url = new org.codehaus.plexus.util.xml.Xpp3Dom("compilerUrl");
        url.setValue("https://compiler.example.test"); config.addChild(url); execution.setConfiguration(config);
        plugin.addExecution(execution); m.project.getBuild().addPlugin(plugin);
        Assert.assertTrue(Assert.assertThrows(MojoExecutionException.class, m::execute).getMessage().contains("No request sent"));
        m.compilerUrl = "https://compiler.example.test";
        m.checkExecutionUrls();
        // Explicit command-line selection resolves the ambiguity, before credential lookup.
        org.apache.maven.execution.DefaultMavenExecutionRequest request = new org.apache.maven.execution.DefaultMavenExecutionRequest();
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("fluxtion.compilerUrl", "https://chosen.example.test"); request.setUserProperties(properties);
        m.session = new org.apache.maven.execution.MavenSession(null, (org.eclipse.aether.RepositorySystemSession) null,
                request, new org.apache.maven.execution.DefaultMavenExecutionResult());
        m.checkExecutionUrls();
        Assert.assertEquals("https://chosen.example.test", m.compilerUrl);
        m.session.getUserProperties().clear();
        m.compilerUrl = null;
        org.apache.maven.project.MavenProject child = m.project;
        m.project = new org.apache.maven.project.MavenProject();
        m.session.setProjects(java.util.Arrays.asList(m.project, child));
        Assert.assertThrows(MojoExecutionException.class, m::checkExecutionUrls);
        execution.setConfiguration(null);
        plugin.setConfiguration(config);
        Assert.assertThrows(MojoExecutionException.class, m::checkExecutionUrls);
        plugin.setConfiguration(null);
        child.getProperties().setProperty("fluxtion.compilerUrl", "https://child.example.test");
        Assert.assertThrows(MojoExecutionException.class, m::checkExecutionUrls);
    }

    private FluxtionAccountMojo mojo(StubCompilerServer server, CredentialStore store, RecordingLog log) {
        FluxtionAccountMojo m = new FluxtionAccountMojo(); m.env = k -> null; m.credentialStore = store;
        m.compilerUrl = server.url(); m.setLog(log); return m;
    }
}
