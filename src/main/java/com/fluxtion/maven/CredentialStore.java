package com.fluxtion.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;

/**
 * The developer machine's stored compiler credential, written by {@code fluxtion:login} and removed by
 * {@code fluxtion:logout}. This file is the only place the plugin ever writes a credential.
 *
 * <p>Location: {@code ~/.fluxtion/credentials}, overridable with the {@code fluxtion.credentialsFile} system
 * property. On a POSIX file system the directory is created {@code rwx------} and the file {@code rw-------}, and the
 * file is created with those permissions before any secret is written to it. On Windows (no POSIX permissions) the
 * file inherits the ACL of the user's profile directory, which by default only the user and administrators can read;
 * the plugin additionally clears the read and write flags for other users where the JVM allows it.</p>
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
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (posix) {
            FileAttribute<Set<PosixFilePermission>> ownerOnly =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            try {
                Files.createFile(tmp, ownerOnly);
            } catch (FileAlreadyExistsException e) {
                throw new IOException("could not create " + tmp + " exclusively", e);
            }
        } else {
            Files.createFile(tmp);
            restrictNonPosix(tmp);
        }
        Properties p = new Properties();
        p.setProperty(KEY_TOKEN, s.token);
        if (s.credentialId != null) p.setProperty(KEY_CREDENTIAL_ID, s.credentialId);
        if (s.accountId != null) p.setProperty(KEY_ACCOUNT_ID, s.accountId);
        if (s.url != null) p.setProperty(KEY_URL, s.url);
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Fluxtion compiler credential - written by mvn fluxtion:login. Do not commit or share.");
        } catch (IOException e) {
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

    private static void restrictNonPosix(Path p) {
        java.io.File f = p.toFile();
        // clear for everyone, then grant the owner; best effort, the profile directory's ACL is the real boundary
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setReadable(true, true);
        f.setWritable(true, true);
    }
}
