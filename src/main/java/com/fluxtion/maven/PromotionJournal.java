package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One promotion into {@code src/} as a transaction that a {@code kill -9} cannot leave half done.
 *
 * <p><b>Why a journal and not a directory swap.</b> A processor's files live in two directories (the source package
 * and the resources package), and either may hold files that are not the generator's, which promotion must leave
 * alone. Two directories cannot be renamed into place in one atomic step, and swapping a whole package would carry
 * the user's own files through the swap. So the commit is:
 * <ol>
 *   <li><b>prepare</b>: every new file is written beside its destination as {@code .<name>.<tx>.promote}. A kill here
 *   leaves {@code src/} wholly old; the temporaries are debris that {@link #recover} deletes.</li>
 *   <li><b>commit point</b>: the journal {@code .fluxtion-promote.json} is renamed into the resources package. It lists
 *   every rename and delete, in order, with the expected content hash of each result.</li>
 *   <li><b>apply</b>: renames (sources, then resources, then the request stamp, then the ownership marker) and
 *   deletes; then the journal is deleted. A kill here leaves the journal, and {@link #recover} rolls the transaction
 *   forward: every rename and delete is idempotent, so applying the rest gives wholly the new generation.</li>
 * </ol>
 * {@code recover} runs before anything reads {@code src/}: in the {@code prepare} goal (before the first compile), at
 * the start of the scan, and at the start of promote.
 *
 * <p><b>Concurrency and durability.</b> Commit and recover hold an exclusive lock ({@link #LOCK} in the build
 * directory, a file lock across processes plus an in-JVM lock), so an IDE build and a command-line build cannot
 * interleave two transactions; a commit that finds another transaction's journal completes it first and never
 * replaces it. Every prepared file and the journal are forced to disk before the commit rename, and the directories
 * after it, so a power loss cannot leave a journal pointing at temporaries that were never written.
 */
final class PromotionJournal {

    static final String JOURNAL = ".fluxtion-promote.json";
    /** The promotion lock, in the build directory: an IDE build and a command-line build of one project share it. */
    static final String LOCK = "fluxtion-promote.lock";
    /** In-JVM guard: a FileChannel lock is per process, so two threads of one JVM need their own exclusion. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> JVM_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final String FORMAT = "fluxtion-promote-journal/1";
    private static final Pattern TEMP = Pattern.compile("^\\..+\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.promote$");
    /** Fault injection for tests and the consistency harness: a pause after each applied step. */
    static final String FAULT_PAUSE_PROPERTY = "fluxtion.promote.faultPauseMillis";

    /** One planned change: {@code bytes == null} deletes {@code path}. */
    static final class Op {
        final Path path;
        final byte[] bytes;

        Op(Path path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }

    private PromotionJournal() {
    }

    /**
     * Applies {@code ops} as one transaction, journaled in {@code journalDir}. Ops whose destination already holds
     * exactly the bytes (or is already absent, for a delete) are dropped first. Returns the number of files written
     * and deleted, as {@code [written, deleted]}.
     */
    static int[] commit(Path journalDir, List<Op> ops, Path lockFile, Log log) throws IOException {
        try (Held held = lock(lockFile)) {
            if (Files.isRegularFile(journalDir.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    recoverLocked(journalDir, new ArrayList<>(), log);
                } catch (MojoExecutionException unrecoverable) {
                    throw new IOException(unrecoverable.getMessage(), unrecoverable);
                }
            }
            return commitLocked(journalDir, ops, log);
        }
    }

    private static int[] commitLocked(Path journalDir, List<Op> ops, Log log) throws IOException {
        List<Op> needed = new ArrayList<>();
        for (Op op : ops) {
            boolean exists = Files.isRegularFile(op.path, LinkOption.NOFOLLOW_LINKS);
            if (op.bytes == null ? exists : !(exists && Arrays.equals(op.bytes, Files.readAllBytes(op.path)))) {
                needed.add(op);
            }
        }
        int[] counts = new int[2];
        if (needed.isEmpty()) {
            return counts;
        }
        String tx = UUID.randomUUID().toString();
        Files.createDirectories(journalDir);
        Map<Op, Path> temps = new LinkedHashMap<>();
        try {
            for (Op op : needed) {
                if (op.bytes == null) {
                    continue;
                }
                Path dir = op.path.toAbsolutePath().getParent();
                Files.createDirectories(dir);
                // not Files.createTempFile: that file is owner-only (0600), and the rename would carry it into src/
                Path temp = dir.resolve("." + op.path.getFileName() + "." + tx + ".promote");
                writeDurably(temp, op.bytes);
                if (Files.isRegularFile(op.path, LinkOption.NOFOLLOW_LINKS) && isPosix(op.path)) {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(op.path, LinkOption.NOFOLLOW_LINKS));
                }
                temps.put(op, temp);
            }
            // the commit point: from here the transaction is completed, by this build or the next
            StringBuilder json = new StringBuilder("{\n  \"format\": ").append(Json.quote(FORMAT))
                    .append(",\n  \"tx\": ").append(Json.quote(tx)).append(",\n  \"ops\": [");
            int i = 0;
            for (Op op : needed) {
                json.append(i++ == 0 ? "\n" : ",\n").append("    {\"path\": ")
                        .append(Json.quote(relative(journalDir, op.path)));
                if (op.bytes != null) {
                    json.append(", \"temp\": ").append(Json.quote(relative(journalDir, temps.get(op))))
                            .append(", \"sha256\": ").append(Json.quote(sha256(op.bytes)));
                }
                json.append("}");
            }
            json.append("\n  ]\n}\n");
            Path journalTemp = journalDir.resolve("." + JOURNAL.substring(1) + "." + tx + ".promote");
            writeDurably(journalTemp, json.toString().getBytes(StandardCharsets.UTF_8));
            for (Path temp : temps.values()) syncDirectory(temp.getParent());
            // the commit point. Not REPLACE_EXISTING: under the lock no other journal can be there, and if one were,
            // replacing it would lose that transaction
            try {
                Files.move(journalTemp, journalDir.resolve(JOURNAL), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(journalTemp, journalDir.resolve(JOURNAL));
            }
            syncDirectory(journalDir);
        } catch (IOException | RuntimeException failed) {
            // before the commit point: nothing in src/ changed, so the temporaries go and src/ is wholly old
            for (Path temp : temps.values()) {
                Files.deleteIfExists(temp);
            }
            throw failed;
        }
        long pause = Long.getLong(FAULT_PAUSE_PROPERTY, 0L);
        for (Op op : needed) {
            if (op.bytes == null) {
                Files.deleteIfExists(op.path);
                counts[1]++;
            } else {
                move(temps.get(op), op.path);
                counts[0]++;
            }
            if (pause > 0) {
                log.info("Fluxtion promote: fault injection pause after " + op.path.getFileName());
                sleep(pause);
            }
        }
        for (Op op : needed) syncDirectory(op.path.toAbsolutePath().getParent());
        Files.delete(journalDir.resolve(JOURNAL));
        syncDirectory(journalDir);
        return counts;
    }

    /**
     * Completes an interrupted promotion under {@code journalDir}, or removes a prepare step's debris. Idempotent.
     * {@code debrisDirs} are the package directories whose stray temporaries are deleted. Returns true when a
     * journal was rolled forward.
     */
    static boolean recover(Path journalDir, List<Path> debrisDirs, Path lockFile, Log log) throws MojoExecutionException {
        try (Held held = lock(lockFile)) {
            return recoverLocked(journalDir, debrisDirs, log);
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion: cannot take the promotion lock " + lockFile + ": " + e.getMessage(), e);
        }
    }

    private static boolean recoverLocked(Path journalDir, List<Path> debrisDirs, Log log) throws MojoExecutionException {
        Path journal = journalDir.resolve(JOURNAL);
        boolean rolled = false;
        try {
            if (Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                Map<String, Object> fields;
                try {
                    fields = Json.objectStrict(new String(Files.readAllBytes(journal), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException malformed) {
                    throw new MojoExecutionException("Fluxtion: the promotion journal " + journal + " is unreadable ("
                            + malformed.getMessage() + "). A promotion into src/ was interrupted and cannot be"
                            + " completed: restore the generated package from version control, delete the journal,"
                            + " and build with -Pgenerate-fluxtion.");
                }
                if (!FORMAT.equals(fields.get("format")) || !(fields.get("ops") instanceof List)) {
                    throw new MojoExecutionException("Fluxtion: " + journal + " is not a promotion journal this plugin"
                            + " understands (format " + fields.get("format") + "); delete it after restoring the"
                            + " generated package from version control.");
                }
                for (Object entry : (List<?>) fields.get("ops")) {
                    @SuppressWarnings("unchecked") Map<String, Object> op = (Map<String, Object>) entry;
                    Path path = journalDir.resolve((String) op.get("path")).normalize();
                    Object temp = op.get("temp");
                    if (temp == null) {
                        Files.deleteIfExists(path);
                        continue;
                    }
                    Path tempPath = journalDir.resolve((String) temp).normalize();
                    if (Files.isRegularFile(tempPath, LinkOption.NOFOLLOW_LINKS)) {
                        move(tempPath, path);
                    } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || !sha256(Files.readAllBytes(path)).equals(op.get("sha256"))) {
                        throw new MojoExecutionException("Fluxtion: an interrupted promotion (" + journal + ") cannot"
                                + " be completed: " + path + " is neither the new file nor waiting to become it."
                                + " Restore the generated package from version control, delete the journal, and"
                                + " build with -Pgenerate-fluxtion.");
                    }
                }
                Files.delete(journal);
                rolled = true;
                log.warn("Fluxtion: completed a promotion into src/ that an earlier build was stopped in the middle of"
                        + " (" + journal.getParent() + "); the generated package is now wholly the new generation.");
            }
            int removed = 0;
            for (Path dir : debrisDirs) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                try (Stream<Path> list = Files.list(dir)) {
                    for (Path p : list.filter(p -> TEMP.matcher(p.getFileName().toString()).matches())
                            .collect(Collectors.toList())) {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                }
            }
            if (removed > 0) {
                log.info("Fluxtion: removed " + removed + " temporary file(s) left by an interrupted promotion;"
                        + " src/ was not changed by it.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion: could not complete an interrupted promotion in " + journalDir
                    + ": " + e.getMessage(), e);
        }
        return rolled;
    }

    /** Recovers every journal found under {@code resourcesRoot} (the scan does not know the generated package). */
    static void recoverAll(Path resourcesRoot, Path lockFile, Log log) throws MojoExecutionException {
        if (!Files.isDirectory(resourcesRoot)) return;
        List<Path> journals;
        try (Stream<Path> walk = Files.walk(resourcesRoot, 32, new FileVisitOption[0])) {
            journals = walk.filter(p -> p.getFileName().toString().equals(JOURNAL)).collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion: could not look for interrupted promotions under " + resourcesRoot, e);
        }
        for (Path journal : journals) {
            recover(journal.getParent(), new ArrayList<>(), lockFile, log);
        }
    }

    /**
     * A journal or a prepared temporary. Resource copying runs before the scan's recovery, so after a kill these can
     * reach {@code target/classes}; promote removes them from there.
     */
    static boolean isDebris(String fileName) {
        return JOURNAL.equals(fileName) || TEMP.matcher(fileName).matches();
    }

    private static String relative(Path journalDir, Path path) {
        return journalDir.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /** An exclusive hold on the promotion lock: in-JVM first, then the file lock across processes. */
    static final class Held implements AutoCloseable {
        private final java.util.concurrent.locks.ReentrantLock jvm;
        private final java.nio.channels.FileChannel channel;
        private final java.nio.channels.FileLock fileLock;

        Held(java.util.concurrent.locks.ReentrantLock jvm, java.nio.channels.FileChannel channel,
             java.nio.channels.FileLock fileLock) {
            this.jvm = jvm;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() throws IOException {
            try {
                if (fileLock != null && fileLock.isValid()) fileLock.release();
                if (channel != null) channel.close();
            } finally {
                jvm.unlock();
            }
        }
    }

    static Held lock(Path lockFile) throws IOException {
        Path absolute = lockFile.toAbsolutePath().normalize();
        java.util.concurrent.locks.ReentrantLock jvm =
                JVM_LOCKS.computeIfAbsent(absolute.toString(), k -> new java.util.concurrent.locks.ReentrantLock());
        jvm.lock();
        try {
            if (jvm.getHoldCount() > 1) {
                return new Held(jvm, null, null); // re-entered by this thread: the file lock is already held
            }
            Files.createDirectories(absolute.getParent());
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new Held(jvm, channel, channel.lock());
            } catch (IOException | RuntimeException failed) {
                channel.close();
                throw failed;
            }
        } catch (IOException | RuntimeException failed) {
            jvm.unlock();
            throw failed;
        }
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /** Best effort: a directory entry is durable once its directory is synced; not every platform allows it. */
    private static void syncDirectory(Path dir) {
        if (dir == null) return;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException unsupported) {
            // e.g. Windows cannot open a directory; the rename itself is still atomic
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean isPosix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
