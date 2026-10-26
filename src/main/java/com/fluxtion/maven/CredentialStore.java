package com.fluxtion.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/**
 * The developer machine's stored compiler credential, written by {@code fluxtion:login} and removed by
 * {@code fluxtion:logout}. This file is the only place the plugin ever writes a credential.
 *
 * <p>Location: {@code ~/.fluxtion/credentials}, overridable with the {@code fluxtion.credentialsFile} system
 * property. On a POSIX file system the directory is created {@code rwx------} and the file {@code rw-------}, and the
 * file is created with those permissions before any secret is written to it. On Windows (no POSIX permissions) the
 * plugin replaces the file's access list with one entry for its owner before the secret is written.</p>
 */
final class CredentialStore {

    static final String FILE_PROPERTY = "fluxtion.credentialsFile";

    private static final String KEY_TOKEN = "token";
    private static final String KEY_CREDENTIAL_ID = "credential_id";
    private static final String KEY_ACCOUNT_ID = "account_id";
    private static final String KEY_URL = "url";

    /** A stored credential. {@link #toString()} never includes the secret. */
    static final class Stored {
        final String token;
        final String credentialId;
        final String accountId;
        final String url;

        Stored(String token, String credentialId, String accountId, String url) {
            this.token = token;
            this.credentialId = credentialId;
            this.accountId = accountId;
            this.url = url;
        }

        @Override
        public String toString() {
            return "credential " + credentialId + " for account " + accountId + " at " + url;
        }
    }

    private final Path file;

    CredentialStore(Path file) {
        this.file = file;
    }

    static CredentialStore defaultStore() {
        String override = System.getProperty(FILE_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return new CredentialStore(Paths.get(override.trim()));
        }
        return new CredentialStore(Paths.get(System.getProperty("user.home"), ".fluxtion", "credentials"));
    }

    Path file() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** The stored credential, or null when there is none (or the file holds no token). */
    Stored read() throws IOException {
        if (!exists()) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        String token = p.getProperty(KEY_TOKEN);
        if (token == null || token.trim().isEmpty()) return null;
        return new Stored(token.trim(), p.getProperty(KEY_CREDENTIAL_ID), p.getProperty(KEY_ACCOUNT_ID),
                p.getProperty(KEY_URL));
    }

    /** True when the file is readable or writable by anyone but its owner (POSIX only; false elsewhere). */
    boolean tooOpen() {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            for (PosixFilePermission perm : perms) {
                switch (perm) {
                    case OWNER_READ:
                    case OWNER_WRITE:
                    case OWNER_EXECUTE:
                        break;
                    default:
                        return true;
                }
            }
            return false;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    /** Writes the credential, owner-only from the moment the file exists, replacing any earlier one atomically. */
    void write(Stored s) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        // a fresh name each time, created exclusively (CREATE_NEW never follows a planted link) and written through
        // the same handle that created it: nothing can be swapped in between creation and the secret being written
        Path tmp = dir.resolve(file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        Properties p = new Properties();
        p.setProperty(KEY_TOKEN, s.token);
        if (s.credentialId != null) p.setProperty(KEY_CREDENTIAL_ID, s.credentialId);
        if (s.accountId != null) p.setProperty(KEY_ACCOUNT_ID, s.accountId);
        if (s.url != null) p.setProperty(KEY_URL, s.url);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        p.store(bytes, "Fluxtion compiler credential - written by mvn fluxtion:login. Do not commit or share.");
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        try (SeekableByteChannel ch = Files.newByteChannel(tmp, options, attrs)) {
            if (!posix) restrictNonPosix(tmp);
            ByteBuffer buf = ByteBuffer.wrap(bytes.toByteArray());
            while (buf.hasRemaining()) ch.write(buf);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("could not create " + tmp + " exclusively", e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** Deletes the stored credential; true if there was one. */
    boolean delete() throws IOException {
        return Files.deleteIfExists(file);
    }

    /**
     * Windows: replace the inherited access list with a single entry for the file's owner, before anything is
     * written. {@code File.setReadable(false, false)} does nothing there, so it is not relied on.
     */
    static void restrictNonPosix(Path p) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (acl == null) {
            java.io.File f = p.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
            return;
        }
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(Collections.singletonList(ownerOnly));
    }
}
