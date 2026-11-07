package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

/** The promotion transaction: a kill before the commit point leaves src/ wholly old, after it the next build finishes. */
public class PromotionJournalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RecordingLog log = new RecordingLog();

    private Path lock() {
        return tmp.getRoot().toPath().resolve("target").resolve(PromotionJournal.LOCK);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static long entries(Path dir) throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            return list.count();
        }
    }

    @Test
    public void aCommitAppliesEveryOpAndLeavesNoJournal() throws Exception {
        Path java = tmp.newFolder("java").toPath();
        Path res = tmp.newFolder("res").toPath();
        Files.write(java.resolve("P.java"), b("old source"));
        Files.write(res.resolve("P.graphml"), b("old graph"));
        Files.write(res.resolve("P.png"), b("png"));
        int[] counts = PromotionJournal.commit(res, Arrays.asList(
                new PromotionJournal.Op(java.resolve("P.java"), b("new source")),
                new PromotionJournal.Op(res.resolve("P.graphml"), b("old graph")),
                new PromotionJournal.Op(res.resolve("P.png"), null)), lock(), log);
        Assert.assertArrayEquals("one written, one deleted, the unchanged one untouched", new int[]{1, 1}, counts);
        Assert.assertEquals("new source", read(java.resolve("P.java")));
        Assert.assertFalse(Files.exists(res.resolve("P.png")));
        Assert.assertFalse(Files.exists(res.resolve(PromotionJournal.JOURNAL)));
        Assert.assertEquals(1, entries(java));
        Assert.assertEquals(1, entries(res));
    }

    /** A kill after the commit point, simulated: one rename done, one pending. The next build rolls forward. */
    @Test
    public void anInterruptedCommitIsRolledForward() throws Exception {
        Path java = tmp.newFolder("java").toPath();
        Path res = tmp.newFolder("res").toPath();
        String tx = "11111111-2222-3333-4444-555555555555";
        Files.write(java.resolve("P.java"), b("new source")); // already renamed
        Files.write(res.resolve("P.fluxtion-request.json"), b("old stamp"));
        Files.write(res.resolve(".P.fluxtion-request.json." + tx + ".promote"), b("new stamp"));
        Files.write(res.resolve("P.png"), b("png"));
        String journal = "{\"format\": \"" + PromotionJournal.FORMAT + "\", \"tx\": \"" + tx + "\", \"ops\": ["
                + "{\"path\": \"../java/P.java\", \"temp\": \"../java/.P.java." + tx + ".promote\", \"sha256\": \""
                + PromotionJournal.sha256(b("new source")) + "\"},"
                + "{\"path\": \"P.fluxtion-request.json\", \"temp\": \".P.fluxtion-request.json." + tx + ".promote\","
                + " \"sha256\": \"" + PromotionJournal.sha256(b("new stamp")) + "\"},"
                + "{\"path\": \"P.png\"}]}";
        Files.write(res.resolve(PromotionJournal.JOURNAL), b(journal));
        Assert.assertTrue(PromotionJournal.recover(res, Arrays.asList(java, res), lock(), log));
        Assert.assertEquals("new source", read(java.resolve("P.java")));
        Assert.assertEquals("new stamp", read(res.resolve("P.fluxtion-request.json")));
        Assert.assertFalse(Files.exists(res.resolve("P.png")));
        Assert.assertEquals("only the stamp remains", 1, entries(res));
        Assert.assertTrue(log.lines.toString(), log.lines.stream().anyMatch(l -> l.contains("completed a promotion")));
        Assert.assertFalse("idempotent: nothing left to do", PromotionJournal.recover(res, Arrays.asList(java, res), lock(), log));
    }

    /** A kill before the commit point leaves only temporaries, which go; src/ is wholly old. */
    @Test
    public void aKillBeforeTheCommitPointLeavesSrcWhollyOld() throws Exception {
        Path res = tmp.newFolder("res").toPath();
        Files.write(res.resolve("P.graphml"), b("old graph"));
        Files.write(res.resolve(".P.graphml.11111111-2222-3333-4444-555555555555.promote"), b("new graph"));
        Files.write(res.resolve(".user-dotfile"), b("kept"));
        Assert.assertFalse(PromotionJournal.recover(res, Collections.singletonList(res), lock(), log));
        Assert.assertEquals("old graph", read(res.resolve("P.graphml")));
        Assert.assertEquals(2, entries(res));
    }

    /** A journal whose pending file is neither the temporary nor the expected result cannot be guessed at. */
    @Test
    public void anUncompletableJournalRefusesWithTheRemedy() throws Exception {
        Path res = tmp.newFolder("res").toPath();
        Files.write(res.resolve("P.java"), b("something else"));
        Files.write(res.resolve(PromotionJournal.JOURNAL), b("{\"format\": \"" + PromotionJournal.FORMAT
                + "\", \"tx\": \"t\", \"ops\": [{\"path\": \"P.java\", \"temp\": \".gone\", \"sha256\": \""
                + PromotionJournal.sha256(b("new")) + "\"}]}"));
        try {
            PromotionJournal.recover(res, Collections.singletonList(res), lock(), log);
            Assert.fail("must refuse");
        } catch (MojoExecutionException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("-Pgenerate-fluxtion"));
        }
    }

    @Test
    public void recoverAllFindsAJournalInAnyPackage() throws Exception {
        Path root = tmp.newFolder("resources").toPath();
        Path pkg = Files.createDirectories(root.resolve("com/acme/generated"));
        Files.write(pkg.resolve(".P.graphml.t.promote"), b("new"));
        Files.write(pkg.resolve(PromotionJournal.JOURNAL), b("{\"format\": \"" + PromotionJournal.FORMAT
                + "\", \"tx\": \"t\", \"ops\": [{\"path\": \"P.graphml\", \"temp\": \".P.graphml.t.promote\", \"sha256\": \""
                + PromotionJournal.sha256(b("new")) + "\"}]}"));
        PromotionJournal.recoverAll(root, lock(), log);
        Assert.assertEquals("new", read(pkg.resolve("P.graphml")));
        Assert.assertFalse(Files.exists(pkg.resolve(PromotionJournal.JOURNAL)));
    }

    /** A commit that finds another transaction's journal completes that one first; it never replaces it. */
    @Test
    public void aCommitNeverReplacesAnotherTransactionsJournal() throws Exception {
        Path res = tmp.newFolder("res").toPath();
        String tx = "11111111-2222-3333-4444-555555555555";
        Files.write(res.resolve("Q.graphml"), b("old q"));
        Files.write(res.resolve(".Q.graphml." + tx + ".promote"), b("new q"));
        Files.write(res.resolve(PromotionJournal.JOURNAL), b("{\"format\": \"" + PromotionJournal.FORMAT + "\", \"tx\": \""
                + tx + "\", \"ops\": [{\"path\": \"Q.graphml\", \"temp\": \".Q.graphml." + tx + ".promote\", \"sha256\": \""
                + PromotionJournal.sha256(b("new q")) + "\"}]}"));
        PromotionJournal.commit(res, Collections.singletonList(new PromotionJournal.Op(res.resolve("P.graphml"), b("p"))),
                lock(), log);
        Assert.assertEquals("the interrupted transaction was completed, not lost", "new q", read(res.resolve("Q.graphml")));
        Assert.assertEquals("p", read(res.resolve("P.graphml")));
        Assert.assertFalse(Files.exists(res.resolve(PromotionJournal.JOURNAL)));
    }

    /** Two concurrent commits (an IDE build and a command-line build) serialise on the lock; each lands whole. */
    @Test
    public void concurrentCommitsSerialiseOnTheLock() throws Exception {
        Path res = tmp.newFolder("res").toPath();
        int rounds = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int t = 0; t < 2; t++) {
            String who = "writer" + t;
            done.add(pool.submit(() -> {
                for (int i = 0; i < rounds; i++) {
                    String v = who + "-" + i;
                    PromotionJournal.commit(res, Arrays.asList(
                            new PromotionJournal.Op(res.resolve("P.java"), b(v)),
                            new PromotionJournal.Op(res.resolve("P.fluxtion-request.json"), b(v))), lock(), log);
                    Assert.assertFalse(Files.exists(res.resolve(PromotionJournal.JOURNAL)));
                }
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : done) f.get(2, java.util.concurrent.TimeUnit.MINUTES);
        pool.shutdown();
        Assert.assertEquals("the pair always comes from one transaction", read(res.resolve("P.java")),
                read(res.resolve("P.fluxtion-request.json")));
        Assert.assertEquals("no temporaries left", 2, entries(res));
    }

    /** The lock is a real file lock: another process (simulated by a second channel) waits for it. */
    @Test
    public void theLockIsHeldAcrossTheTransaction() throws Exception {
        Path lock = lock();
        try (PromotionJournal.Held held = PromotionJournal.lock(lock);
             java.nio.channels.FileChannel other = java.nio.channels.FileChannel.open(lock,
                     java.nio.file.StandardOpenOption.WRITE)) {
            try {
                Assert.assertNull("held by this transaction", other.tryLock());
            } catch (java.nio.channels.OverlappingFileLockException sameJvm) {
                // the JVM reports the overlap directly: the lock is held
            }
        }
    }
}
