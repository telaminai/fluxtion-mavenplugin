package com.fluxtion.maven;

import com.telamin.fluxtion.Fluxtion;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Credential precedence and hand-off to the builder, through the scan goal and the stand-in builder. */
public class CompilerCredentialsTest {

    private static final String ENV_TOKEN_VALUE = "fxk_ci.env-secret";
    private static final String FILE_TOKEN_VALUE = "fxk_dev.file-secret";

    private MavenProject project;
    private FluxtionScanToGenMojo mojo;
    private Path home;
    private final Map<String, String> env = new HashMap<>();
    private RecordingLog log;

    @Before
    public void setUp() throws Exception {
        Fluxtion.reset();
        project = mock(MavenProject.class);
        File baseDir = new File("target/test-mojo-auth").getAbsoluteFile();
        baseDir.mkdirs();
        when(project.getBasedir()).thenReturn(baseDir);
        Build build = mock(Build.class);
        when(project.getBuild()).thenReturn(build);
        when(build.getSourceDirectory()).thenReturn(new File(baseDir, "src/main/java").getAbsolutePath());
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());

        home = Files.createTempDirectory("fluxtion-auth-test");
        mojo = new FluxtionScanToGenMojo();
        mojo.project = project;
        mojo.env = env::get;
        mojo.credentialStore = new CredentialStore(home.resolve(".fluxtion/credentials"));
        log = new RecordingLog();
        mojo.setLog(log);
        for (String p : new String[]{"fluxtion.remote.auth", "fluxtion.remote.token", "fluxtion.remote.host",
                "fluxtion.remote.port"}) {
            System.clearProperty(p);
        }
    }

    @After
    public void tearDown() throws Exception {
        try (java.util.stream.Stream<Path> s = Files.walk(home)) {
            s.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private void storeCredential(String url) throws Exception {
        mojo.credentialStore.write(new CredentialStore.Stored(FILE_TOKEN_VALUE, "cred-file", "acct-1", url));
    }

    @Test
    public void defaultModeSetsNoAuthProperty() throws Exception {
        mojo.execute();
        Assert.assertTrue(Fluxtion.called);
        Assert.assertNull("default (RapidAPI key) mode passes no fluxtion.remote.auth", Fluxtion.seenAuth);
        Assert.assertNull(Fluxtion.seenToken);
        Assert.assertNull(Fluxtion.seenHost);
        Assert.assertNull(Fluxtion.seenPort);
    }

    /**
     * Backward compatibility: with no credential anywhere, a build behaves exactly as before this plugin knew about
     * compiler accounts, even if FLUXTION_COMPILER_URL holds something that is not a valid compiler URL.
     */
    @Test
    public void withNoCredential_anInvalidCompilerUrlInTheEnvironmentIsIgnored() throws Exception {
        env.put("FLUXTION_COMPILER_URL", "not a url at all");
        mojo.execute();
        Assert.assertTrue(Fluxtion.called);
        Assert.assertNull("default mode: no auth property", Fluxtion.seenAuth);
    }

    @Test
    public void environmentWinsOverTheStoredFile() throws Exception {
        storeCredential("https://stored.example.com");
        env.put("FLUXTION_API_TOKEN", ENV_TOKEN_VALUE);
        mojo.compilerUrl = "https://compiler.example.com:8443";
        mojo.execute();
        Assert.assertEquals("token", Fluxtion.seenAuth);
        Assert.assertEquals(ENV_TOKEN_VALUE, Fluxtion.seenToken);
        Assert.assertEquals("https://compiler.example.com", Fluxtion.seenHost);
        Assert.assertEquals("8443", Fluxtion.seenPort);
        assertNoCredentialLeft();
    }

    @Test
    public void storedFileUsedWithoutEnvironment() throws Exception {
        storeCredential("https://stored.example.com");
        mojo.execute();
        Assert.assertEquals("token", Fluxtion.seenAuth);
        Assert.assertEquals(FILE_TOKEN_VALUE, Fluxtion.seenToken);
        Assert.assertEquals("https://stored.example.com", Fluxtion.seenHost);
        Assert.assertEquals("443", Fluxtion.seenPort);
        assertNoCredentialLeft();
    }

    @Test
    public void environmentTokenWithoutUrlIsRefused() throws Exception {
        // without a URL the builder would post the bearer credential to its default host, the RapidAPI gateway
        env.put("FLUXTION_API_TOKEN", ENV_TOKEN_VALUE);
        try {
            mojo.execute();
            Assert.fail("expected refusal");
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("FLUXTION_COMPILER_URL"));
            Assert.assertFalse(e.getMessage().contains(ENV_TOKEN_VALUE));
        }
        Assert.assertFalse("the builder must not run", Fluxtion.called);
        assertNoCredentialLeft();
    }

    @Test
    public void storedCredentialIsNeverSentToAnotherCompiler() throws Exception {
        storeCredential("https://stored.example.com");
        mojo.compilerUrl = "https://other.example.com";
        try {
            mojo.execute();
            Assert.fail("expected refusal");
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("https://stored.example.com"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("fluxtion:login"));
            Assert.assertFalse(e.getMessage().contains(FILE_TOKEN_VALUE));
        }
        Assert.assertFalse("the builder must not run", Fluxtion.called);
        assertNoCredentialLeft();
    }

    @Test
    public void storedCredentialAcceptsTheSameOriginSpelledDifferently() throws Exception {
        storeCredential("https://stored.example.com");
        mojo.compilerUrl = "https://STORED.example.com:443/";
        mojo.execute();
        Assert.assertEquals(FILE_TOKEN_VALUE, Fluxtion.seenToken);
        Assert.assertEquals("443", Fluxtion.seenPort);
        assertNoCredentialLeft();
    }

    @Test
    public void tokenInParentPomFailsTheBuild() {
        MavenProject parent = mock(MavenProject.class);
        Model parentModel = new Model();
        parentModel.setArtifactId("corp-parent");
        parentModel.getProperties().setProperty("fluxtion.remote.token", "fxk_pom.leaked");
        when(parent.getOriginalModel()).thenReturn(parentModel);
        when(project.getOriginalModel()).thenReturn(new Model());
        when(project.getParent()).thenReturn(parent);
        assertRefused();
    }

    @Test
    public void tokenInMvnMavenConfigFailsTheBuild() throws Exception {
        Path root = home.resolve("repo");
        Path module = root.resolve("module");
        Files.createDirectories(module);
        Files.createDirectories(root.resolve(".mvn"));
        Files.write(root.resolve(".mvn/maven.config"),
                "-T 1C\n-D fluxtion.remote.token=fxk_pom.leaked\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(project.getBasedir()).thenReturn(module.toFile());
        assertRefused();
    }

    @Test
    public void parallelGoalsDoNotOverlapOnTheBuilderProperties() throws Exception {
        CompilerCredentials.Resolved a = new CompilerCredentials.Resolved(CompilerCredentials.Source.STORED,
                "fxk_a.secret", CompilerUrl.parse("https://a.example.com"));
        CompilerCredentials.Resolved b = new CompilerCredentials.Resolved(CompilerCredentials.Source.STORED,
                "fxk_b.secret", CompilerUrl.parse("https://b.example.com"));
        Map<String, String> first = CompilerCredentials.apply(a, log);
        java.util.concurrent.CountDownLatch bApplied = new java.util.concurrent.CountDownLatch(1);
        String[] bSaw = new String[1];
        Thread other = new Thread(() -> {
            Map<String, String> second = CompilerCredentials.apply(b, log);
            try {
                bApplied.countDown();
                bSaw[0] = System.getProperty("fluxtion.remote.token");
            } finally {
                CompilerCredentials.restore(second);
            }
        });
        other.start();
        try {
            Assert.assertFalse("a second goal must wait while the first one's builder runs",
                    bApplied.await(300, java.util.concurrent.TimeUnit.MILLISECONDS));
            Assert.assertEquals("fxk_a.secret", System.getProperty("fluxtion.remote.token"));
        } finally {
            CompilerCredentials.restore(first);
        }
        other.join(5_000);
        Assert.assertEquals("fxk_b.secret", bSaw[0]);
        assertNoCredentialLeft();
    }

    @Test
    public void earlierPropertiesAreRestored() throws Exception {
        System.setProperty("fluxtion.remote.host", "https://before.example.com");
        try {
            env.put("FLUXTION_API_TOKEN", ENV_TOKEN_VALUE);
            mojo.compilerUrl = "https://compiler.example.com";
            mojo.execute();
            Assert.assertEquals("https://compiler.example.com", Fluxtion.seenHost);
            Assert.assertEquals("https://before.example.com", System.getProperty("fluxtion.remote.host"));
        } finally {
            System.clearProperty("fluxtion.remote.host");
        }
    }

    @Test
    public void tokenInPluginConfigurationFailsTheBuild() {
        mojo.token = "fxk_pom.leaked";
        assertRefused();
        mojo.token = null;
        mojo.apiToken = "fxk_pom.leaked";
        assertRefused();
    }

    @Test
    public void tokenInPomPropertiesFailsTheBuild() {
        Model model = new Model();
        model.getProperties().setProperty("fluxtion.remote.token", "fxk_pom.leaked");
        when(project.getOriginalModel()).thenReturn(model);
        assertRefused();

        Model withProfile = new Model();
        Profile profile = new Profile();
        profile.setId("ci");
        profile.getProperties().setProperty("FLUXTION_API_TOKEN", "fxk_pom.leaked");
        withProfile.addProfile(profile);
        when(project.getOriginalModel()).thenReturn(withProfile);
        assertRefused();
    }

    @Test
    public void credentialInCompilerUrlFailsTheBuild() {
        mojo.compilerUrl = "https://user:fxk_pom.leaked@compiler.example.com";
        env.put("FLUXTION_API_TOKEN", ENV_TOKEN_VALUE);
        try {
            mojo.execute();
            Assert.fail("expected refusal");
        } catch (MojoExecutionException e) {
            Assert.assertFalse(e.getMessage().contains("fxk_pom.leaked"));
            Assert.assertFalse(Fluxtion.called);
        }
    }

    @Test
    public void credentialIsNeverLogged() throws Exception {
        storeCredential("https://stored.example.com");
        mojo.execute();
        Assert.assertFalse(log.all().contains(FILE_TOKEN_VALUE));
        Assert.assertTrue(log.all(), log.all().contains("signed-in mode"));
    }

    private void assertRefused() {
        Fluxtion.reset();
        try {
            mojo.execute();
            Assert.fail("expected the POM credential to be refused");
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("FLUXTION_API_TOKEN"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("fluxtion:login"));
            Assert.assertFalse(e.getMessage().contains("fxk_pom.leaked"));
            Assert.assertFalse("the builder must not run", Fluxtion.called);
        }
    }

    private void assertNoCredentialLeft() {
        Assert.assertNull(System.getProperty("fluxtion.remote.auth"));
        Assert.assertNull(System.getProperty("fluxtion.remote.token"));
        Assert.assertNull(System.getProperty("fluxtion.remote.host"));
        Assert.assertNull(System.getProperty("fluxtion.remote.port"));
    }
}
