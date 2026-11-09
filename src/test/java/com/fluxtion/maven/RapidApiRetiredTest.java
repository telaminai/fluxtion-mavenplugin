package com.fluxtion.maven;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAPID-OFF (plugin 1.3.4): the RapidAPI key route is retired. The plugin never names it, the builder reads the
 * credential this plugin stores in a format both sides agree on, and signing in needs no compiler URL.
 */
public class RapidApiRetiredTest {

    /** The sweep: no main source names the gateway's host, its key headers or the key file. */
    @Test
    public void noMainSourceNamesTheGatewayHostItsKeyHeadersOrTheKeyFile() throws IOException {
        List<String> found = new ArrayList<>();
        Path dir = Paths.get("src/main");
        Assert.assertTrue("sweep root must exist: " + dir.toAbsolutePath(), Files.isDirectory(dir));
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                for (String term : new String[]{"rapidapi.com", "x-rapidapi", "apikeyfile"}) {
                    if (text.contains(term)) found.add(f + ": " + term);
                }
            }
        }
        Assert.assertEquals("the retired key route is named in main sources", Collections.emptyList(), found);
    }

    /**
     * The stored credential's format is a contract with fluxtion-builder 1.0.90, which reads the same file so that
     * Gradle, JBang and a plain main find the sign-in too: a properties file whose {@code token} is the secret and
     * whose {@code url} is the issuing compiler. Changing either key breaks every non-Maven build.
     */
    @Test
    public void theStoredCredentialKeepsTheKeysTheBuilderReads() throws Exception {
        Path home = Files.createTempDirectory("fluxtion-store-format").toRealPath();
        CredentialStore store = new CredentialStore(home.resolve(".fluxtion/credentials"));
        store.write(new CredentialStore.Stored("fixture-not-a-credential", "cred-1", "acct-1",
                "https://compiler.example.com"));
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(store.file())) {
            p.load(in);
        }
        Assert.assertEquals("fixture-not-a-credential", p.getProperty("token"));
        Assert.assertEquals("https://compiler.example.com", p.getProperty("url"));
    }

    /** {@code mvn fluxtion:login} with no URL signs in to the managed compiler (no network here: the client is stubbed). */
    @Test
    public void loginWithNoUrlSignsInToTheManagedCompiler() throws Exception {
        Path home = Files.createTempDirectory("fluxtion-login-default").toRealPath();
        FluxtionLoginMojo m = new FluxtionLoginMojo();
        m.env = name -> null;
        m.credentialStore = new CredentialStore(home.resolve(".fluxtion/credentials"));
        m.browser = false;
        m.setLog(new RecordingLog());
        CompilerUrl[] seen = new CompilerUrl[1];
        m.clientFactory = url -> {
            seen[0] = url;
            throw new IllegalStateException("stop before any network use");
        };
        try {
            m.execute();
            Assert.fail("the stub stops the sign-in");
        } catch (org.apache.maven.plugin.MojoExecutionException expected) {
            // the stub
        }
        Assert.assertEquals("https://compiler.fluxtion-playground.dev", String.valueOf(seen[0]));
    }
}
