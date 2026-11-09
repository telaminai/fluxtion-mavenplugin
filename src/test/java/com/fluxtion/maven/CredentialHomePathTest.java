package com.fluxtion.maven;

import org.junit.Test;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.Assert.*;

/** Synthetic homes only: platform aliases above the home must not disable signed-out builds. */
public class CredentialHomePathTest {
    private static final CredentialStore.Stored DEMO = new CredentialStore.Stored("synthetic", "id", "acct-demo", "https://compiler.example.com");
    private interface Check { void run(Path root) throws Exception; }
    private void homeFixture(Check check) throws Exception {
        Path root=Files.createTempDirectory("credential-home");
        String home=System.getProperty("user.home"), override=System.getProperty(CredentialStore.FILE_PROPERTY);
        try { System.clearProperty(CredentialStore.FILE_PROPERTY); check.run(root); }
        finally {
            System.setProperty("user.home",home);
            if(override==null)System.clearProperty(CredentialStore.FILE_PROPERTY); else System.setProperty(CredentialStore.FILE_PROPERTY,override);
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path p:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);
            }
        }
    }
    @Test public void symlinkedHomeSupportsSignedOutReadAndRoundTrip() throws Exception {
        homeFixture(root->{
            Path real=Files.createDirectory(root.resolve("home")), alias=root.resolve("alias");
            Files.createSymbolicLink(alias,real);System.setProperty("user.home",alias.toString());
            CredentialStore store=CredentialStore.defaultStore();assertNull(store.read());
            store.write(DEMO);assertEquals("acct-demo",store.read().accountId);
            assertTrue(Files.exists(real.resolve(".fluxtion/credentials")));
            assertTrue(store.compareAndDelete(DEMO));assertNull(store.read());
        });
    }
    @Test public void homeAliasIsResolvedOnceButOwnedDirectoryLinksAreRefused() throws Exception {
        homeFixture(root->{
            Path first=Files.createDirectory(root.resolve("first")), other=Files.createDirectory(root.resolve("other"));
            Path alias=root.resolve("alias");Files.createSymbolicLink(alias,first);System.setProperty("user.home",alias.toString());
            CredentialStore store=CredentialStore.defaultStore();
            Files.delete(alias);Files.createSymbolicLink(alias,other);
            store.write(DEMO);assertTrue(Files.exists(first.resolve(".fluxtion/credentials")));
            assertFalse(Files.exists(other.resolve(".fluxtion/credentials")));
            Files.createSymbolicLink(other.resolve(".fluxtion"),first.resolve(".fluxtion"));
            CredentialStore linked=CredentialStore.defaultStore();
            assertThrows(IOException.class,linked::read);
            assertThrows(IOException.class,()->linked.compareAndDelete(DEMO));
            assertThrows(IOException.class,()->linked.write(DEMO));
        });
    }
    @Test public void explicitOverrideTrustsItsParentButNeverTheCredentialLink() throws Exception {
        homeFixture(root->{
            Path real=Files.createDirectory(root.resolve("custom")), alias=root.resolve("custom-alias");
            Files.createSymbolicLink(alias,real);
            System.setProperty(CredentialStore.FILE_PROPERTY,alias.resolve("credentials").toString());
            CredentialStore store=CredentialStore.defaultStore();assertNull(store.read());store.write(DEMO);
            Path link=real.resolve("credential-link");Files.createSymbolicLink(link,real.resolve("credentials"));
            System.setProperty(CredentialStore.FILE_PROPERTY,alias.resolve("credential-link").toString());
            assertThrows(IOException.class,()->CredentialStore.defaultStore().read());
        });
    }
}
