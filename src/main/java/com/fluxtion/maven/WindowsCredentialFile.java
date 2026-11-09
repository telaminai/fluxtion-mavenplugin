package com.fluxtion.maven;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Windows handle-relative store. Only the volume/share root is opened by absolute name. Every subsequent
 * component is opened with NtCreateFile.RootDirectory and FILE_OPEN_REPARSE_POINT. A directory's name or
 * reparse metadata changing after it was opened cannot redirect an operation through an absolute path.
 * A protected owner-only ACL is supplied at creation; secret I/O, locking, rename and deletion use handles.
 */
final class WindowsCredentialFile {
    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandle(Pointer handle, Pointer information);
        int GetFileType(Pointer handle);
        boolean ReadFile(Pointer handle, byte[] data, int length, IntByReference read, Pointer overlapped);
        boolean WriteFile(Pointer handle, byte[] data, int length, IntByReference written, Pointer overlapped);
        boolean FlushFileBuffers(Pointer handle);
        boolean CloseHandle(Pointer handle);
        Pointer LocalFree(Pointer allocation);
        Pointer GetCurrentProcess();
    }
    interface Nt extends StdCallLibrary {
        int NtCreateFile(PointerByReference handle, int access, ObjectAttributes attributes, IoStatusBlock status,
                         Pointer allocationSize, int fileAttributes, int share, int disposition, int options,
                         Pointer ea, int eaLength);
        int NtLockFile(Pointer handle, Pointer event, Pointer apc, Pointer context, IoStatusBlock status,
                       Pointer offset, Pointer length, int key, byte failImmediately, byte exclusive);
        int NtSetInformationFile(Pointer handle, IoStatusBlock status, Pointer information, int length, int informationClass);
    }
    interface Security extends StdCallLibrary {
        boolean OpenProcessToken(Pointer process, int access, PointerByReference token);
        boolean GetTokenInformation(Pointer token, int informationClass, Pointer information, int length, IntByReference needed);
        int SetEntriesInAclW(int count, ExplicitAccess entries, Pointer oldAcl, PointerByReference newAcl);
        boolean InitializeSecurityDescriptor(Pointer descriptor, int revision);
        boolean SetSecurityDescriptorOwner(Pointer descriptor, Pointer owner, boolean defaulted);
        boolean SetSecurityDescriptorDacl(Pointer descriptor, boolean present, Pointer acl, boolean defaulted);
        boolean SetSecurityDescriptorControl(Pointer descriptor, short bitsOfInterest, short bitsToSet);
    }
    // Public fields/constructors are required by JNA's structure mapper; these contain pointers, never log data.
    public static class UnicodeString extends Structure {
        public short length, maximumLength;
        public Pointer buffer;
        @Override protected List<String> getFieldOrder() { return Arrays.asList("length", "maximumLength", "buffer"); }
    }
    public static class ObjectAttributes extends Structure {
        public int length;
        public Pointer rootDirectory, objectName;
        public int attributes;
        public Pointer securityDescriptor, securityQualityOfService;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("length", "rootDirectory", "objectName", "attributes", "securityDescriptor", "securityQualityOfService");
        }
    }
    public static class IoStatusBlock extends Structure {
        // NTSTATUS/Pointer union followed by ULONG_PTR; both slots are pointer-sized.
        public Pointer status, information;
        @Override protected List<String> getFieldOrder() { return Arrays.asList("status", "information"); }
    }
    public static class Trustee extends Structure {
        public Pointer multipleTrustee;
        public int multipleTrusteeOperation, trusteeForm, trusteeType;
        public Pointer name;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("multipleTrustee", "multipleTrusteeOperation", "trusteeForm", "trusteeType", "name");
        }
    }
    public static class ExplicitAccess extends Structure {
        public int permissions, accessMode, inheritance;
        public Trustee trustee = new Trustee();
        @Override protected List<String> getFieldOrder() { return Arrays.asList("permissions", "accessMode", "inheritance", "trustee"); }
    }
    /** Lazy: portable layout tests can inspect structures without trying to load Windows DLLs. */
    private static final class Api {
        static final Kernel K = Native.load("kernel32", Kernel.class);
        static final Nt N = Native.load("ntdll", Nt.class);
        static final Security S = Native.load("advapi32", Security.class);
    }
    private static final int SYNCHRONIZE = 0x100000, READ_ATTRIBUTES = 0x80, READ_CONTROL = 0x20000,
            WRITE_DAC = 0x40000, DELETE = 0x10000, GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000;
    private static final int OPEN = 1, CREATE = 2, OPEN_IF = 3, SHARE_ALL = 7, SHARE_READ_WRITE = 3;
    private static final int OPEN_REPARSE_POINT = 0x200000, DIRECTORY = 1, NON_DIRECTORY = 0x40, SYNCHRONOUS = 0x20;
    private static final int NAME_NOT_FOUND = 0xc0000034, PATH_NOT_FOUND = 0xc000003a, NAME_COLLISION = 0xc0000035;
    private static final int LOCK_CONFLICT = 0xc0000054, LOCK_NOT_GRANTED = 0xc0000055;
    private static final int MAX_BYTES = 16384;
    private final Path file;
    private final boolean legacyFormat;
    WindowsCredentialFile(Path file) { this(file, false); }
    WindowsCredentialFile(Path file, boolean legacyFormat) { this.file = file; this.legacyFormat = legacyFormat; }

    private static final class NtFailure extends IOException {
        final int status;
        NtFailure(int status) { super("Secure Windows credential operation failed (NT status 0x" + Integer.toHexString(status) + ")."); this.status = status; }
    }
    private static NtFailure ntError(int status) { return new NtFailure(status); }
    private static IOException winError() {
        return new IOException("Secure Windows credential operation failed (Windows error " + Native.getLastError() + ").");
    }
    private static boolean completedSuccess(int status) {
        return status >= 0 && status != 0x103; // NT_SUCCESS, but never treat asynchronous STATUS_PENDING as completed
    }
    private static boolean invalid(Pointer handle) {
        long value = handle == null ? 0 : Pointer.nativeValue(handle);
        return value == 0 || value == -1L || Native.POINTER_SIZE == 4 && value == 0xffffffffL;
    }
    private static final class Handle implements AutoCloseable {
        final Pointer value;
        Handle(Pointer value) { this.value = value; }
        @Override public void close() { Api.K.CloseHandle(value); }
    }
    /** A single component, never a path: callers cannot escape a retained RootDirectory by naming separators. */
    private static void component(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('\\') >= 0
                || name.indexOf('/') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Invalid credential path component.");
    }
    private static Handle relative(Handle parent, String name, int access, int disposition, int share, boolean directory)
            throws IOException {
        component(name);
        byte[] utf16 = name.getBytes(StandardCharsets.UTF_16LE);
        if (utf16.length > 65532) throw new IOException("Credential path component is too long.");
        try (Memory text = new Memory(utf16.length + 2L);
             ProtectedDescriptor creationSecurity = disposition == CREATE ? new ProtectedDescriptor() : null) {
            text.clear(); text.write(0, utf16, 0, utf16.length);
            UnicodeString unicode = new UnicodeString();
            unicode.length = (short) utf16.length; unicode.maximumLength = (short) (utf16.length + 2);
            unicode.buffer = text; unicode.write();
            ObjectAttributes attributes = new ObjectAttributes();
            attributes.length = attributes.size(); attributes.rootDirectory = parent.value;
            attributes.objectName = unicode.getPointer(); attributes.attributes = 0x40; // OBJ_CASE_INSENSITIVE
            attributes.securityDescriptor = creationSecurity == null ? null : creationSecurity.descriptor;
            attributes.write();
            IoStatusBlock io = new IoStatusBlock(); PointerByReference result = new PointerByReference();
            int status = Api.N.NtCreateFile(result, access | SYNCHRONIZE, attributes, io, null, 0x80, share,
                    disposition, OPEN_REPARSE_POINT | SYNCHRONOUS | (directory ? DIRECTORY : NON_DIRECTORY), null, 0);
            if (!completedSuccess(status)) {
                if (!invalid(result.getValue())) Api.K.CloseHandle(result.getValue());
                if (status == NAME_NOT_FOUND || status == PATH_NOT_FOUND) throw new NoSuchFileException("credential component");
                throw ntError(status);
            }
            if (invalid(result.getValue())) throw new IOException("The filesystem returned no credential handle.");
            Handle handle = new Handle(result.getValue());
            try { validate(handle, directory); return handle; }
            catch (IOException e) { handle.close(); throw e; }
        }
    }
    private static void validate(Handle handle, boolean directory) throws IOException {
        try (Memory information = new Memory(64)) {
            if (Api.K.GetFileType(handle.value) != 1 || !Api.K.GetFileInformationByHandle(handle.value, information))
                throw new IOException("Credential storage requires an ordinary disk file or directory.");
            int attrs = information.getInt(0);
            if ((attrs & 0x400) != 0 || ((attrs & 0x10) != 0) != directory)
                throw new IOException("Credential path has a reparse point or the wrong file type.");
        }
    }
    private Handle directory(boolean create) throws IOException {
        // The root names a volume/share. No secret path is subsequently resolved from a DOS/Win32 string.
        Pointer root = Api.K.CreateFileW(new WString(file.getRoot().toString()), READ_ATTRIBUTES | SYNCHRONIZE,
                SHARE_ALL, null, 3, 0x02200000, null); // OPEN_EXISTING, BACKUP_SEMANTICS | OPEN_REPARSE_POINT
        if (invalid(root)) throw winError();
        Handle current = new Handle(root);
        try {
            validate(current, true);
            for (Path part : file.getParent()) {
                String name = part.toString(); Handle next;
                try { next = relative(current, name, READ_ATTRIBUTES, OPEN, SHARE_ALL, true); }
                catch (NoSuchFileException missing) {
                    if (!create) throw missing;
                    // Exclusive creation prevents changing the ACL of a directory somebody else created meanwhile.
                    try {
                        next = relative(current, name, READ_ATTRIBUTES, CREATE, SHARE_ALL, true);
                    } catch (NtFailure creation) {
                        if (creation.status != NAME_COLLISION) throw creation;
                        // A concurrent creator won. Validate its handle, but never alter that existing directory's ACL.
                        next = relative(current, name, READ_ATTRIBUTES, OPEN, SHARE_ALL, true);
                    }
                }
                current.close(); current = next;
            }
            Handle result = current; current = null; return result;
        } finally { if (current != null) current.close(); }
    }
    /**
     * Atomic creation security: there is never an inherited-ACL interval in which another principal can acquire
     * data access or WRITE_DAC and retain it after tightening. The SID buffer and ACL outlive NtCreateFile.
     */
    private static final class ProtectedDescriptor implements AutoCloseable {
        Memory tokenUser, descriptor;
        Pointer acl;
        ProtectedDescriptor() throws IOException {
            try {
                PointerByReference tokenResult = new PointerByReference();
                if (!Api.S.OpenProcessToken(Api.K.GetCurrentProcess(), 8, tokenResult) || invalid(tokenResult.getValue()))
                    throw new IOException("Cannot identify the current user for protected credential creation.");
                try (Handle token = new Handle(tokenResult.getValue())) {
                    IntByReference needed = new IntByReference();
                    boolean unexpectedSuccess = Api.S.GetTokenInformation(token.value, 1, null, 0, needed); // TokenUser
                    if (unexpectedSuccess || Native.getLastError() != 122 || needed.getValue() <= 0 || needed.getValue() > 65536)
                        throw new IOException("Cannot size the current-user security information.");
                    tokenUser = new Memory(needed.getValue());
                    if (!Api.S.GetTokenInformation(token.value, 1, tokenUser, (int) tokenUser.size(), needed))
                        throw new IOException("Cannot read the current-user security information.");
                    Pointer sid = tokenUser.getPointer(0); // TOKEN_USER.User.Sid; remaining bytes stay alive below
                    if (sid == null) throw new IOException("The current user has no usable security identifier.");
                    ExplicitAccess entry = new ExplicitAccess();
                    entry.permissions = 0x1f01ff; entry.accessMode = 2; // FILE_ALL_ACCESS, SET_ACCESS, no inheritance
                    entry.trustee.trusteeForm = 0; entry.trustee.trusteeType = 1; entry.trustee.name = sid;
                    entry.write(); PointerByReference aclResult = new PointerByReference();
                    int status = Api.S.SetEntriesInAclW(1, entry, null, aclResult);
                    acl = aclResult.getValue();
                    if (status != 0 || acl == null) throw new IOException("Cannot construct a protected credential ACL.");
                    // Absolute SECURITY_DESCRIPTOR: four-byte header, aligned owner/group/SACL/DACL pointers.
                    descriptor = new Memory(Native.POINTER_SIZE == 8 ? 40 : 20);
                    descriptor.clear();
                    if (!Api.S.InitializeSecurityDescriptor(descriptor, 1)
                            || !Api.S.SetSecurityDescriptorOwner(descriptor, sid, false)
                            || !Api.S.SetSecurityDescriptorDacl(descriptor, true, acl, false)
                            || !Api.S.SetSecurityDescriptorControl(descriptor, (short) 0x1000, (short) 0x1000))
                        throw new IOException("Cannot initialise protected credential creation security.");
                }
            } catch (IOException | RuntimeException e) { close(); throw e; }
        }
        @Override public void close() {
            if (acl != null) { Api.K.LocalFree(acl); acl = null; }
            if (descriptor != null) { descriptor.close(); descriptor = null; }
            if (tokenUser != null) { tokenUser.close(); tokenUser = null; }
        }
    }
    CredentialStore.Stored read() throws IOException {
        try (Handle parent = directory(false)) { return read(parent); }
        catch (NoSuchFileException e) { return null; }
    }
    private CredentialStore.Stored read(Handle parent) throws IOException {
        try (Handle input = relative(parent, file.getFileName().toString(), GENERIC_READ, OPEN, SHARE_ALL, false)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            while (true) {
                IntByReference count = new IntByReference();
                if (!Api.K.ReadFile(input.value, buffer, buffer.length, count, null)) throw winError();
                int n = count.getValue(); if (n == 0) break;
                if (n < 0 || n > buffer.length || bytes.size() + n > MAX_BYTES) throw new IOException("Credential file is too large.");
                bytes.write(buffer, 0, n);
            }
            return CredentialStore.decode(bytes.toByteArray(), legacyFormat);
        } catch (NoSuchFileException e) { return null; }
    }
    private static boolean same(CredentialStore.Stored a, CredentialStore.Stored b) {
        return a == b || a != null && b != null && Objects.equals(a.token, b.token) && Objects.equals(a.url, b.url)
                && Objects.equals(a.credentialId, b.credentialId) && Objects.equals(a.accountId, b.accountId);
    }
    private static void lock(Handle file) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Memory offset = new Memory(8); Memory length = new Memory(8)) {
            offset.setLong(0, 0); length.setLong(0, 1);
            while (true) {
                int status = Api.N.NtLockFile(file.value, null, null, null, new IoStatusBlock(), offset, length, 0, (byte) 1, (byte) 1);
                if (completedSuccess(status)) return;
                if (status != LOCK_CONFLICT && status != LOCK_NOT_GRANTED) throw ntError(status);
                if (System.nanoTime() - deadline >= 0) throw new IOException("Compiler credential store is busy; retry.");
                try { Thread.sleep(20); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Credential operation cancelled."); }
            }
        }
    }
    private static void remove(Handle handle) throws IOException {
        try (Memory information = new Memory(1)) {
            information.setByte(0, (byte) 1); // FILE_DISPOSITION_INFORMATION.DeleteFile
            int status = Api.N.NtSetInformationFile(handle.value, new IoStatusBlock(), information, 1, 13);
            if (!completedSuccess(status)) throw ntError(status);
        }
    }
    private static void rename(Handle source, Handle parent, String name) throws IOException {
        component(name); byte[] utf16 = name.getBytes(StandardCharsets.UTF_16LE);
        int rootOffset = Native.POINTER_SIZE == 8 ? 8 : 4;
        int lengthOffset = rootOffset + Native.POINTER_SIZE, nameOffset = lengthOffset + 4;
        try (Memory information = new Memory(nameOffset + utf16.length)) {
            information.clear(); information.setByte(0, (byte) 1); // FILE_RENAME_INFORMATION.ReplaceIfExists
            information.setPointer(rootOffset, parent.value); information.setInt(lengthOffset, utf16.length);
            information.write(nameOffset, utf16, 0, utf16.length);
            int status = Api.N.NtSetInformationFile(source.value, new IoStatusBlock(), information, (int) information.size(), 10);
            if (!completedSuccess(status)) throw ntError(status);
        }
    }
    boolean change(CredentialStore.Stored expected, CredentialStore.Stored next, boolean compare) throws IOException {
        synchronized (WindowsCredentialFile.class) {
            try (Handle parent = directory(true);
                 Handle guard = relative(parent, file.getFileName() + ".lock", GENERIC_READ | GENERIC_WRITE,
                         OPEN_IF, SHARE_READ_WRITE, false)) {
                lock(guard); // close releases the lock; no delete sharing keeps its inode stable while held
                if (compare && !same(read(parent), expected)) return false;
                if (next == null) {
                    try (Handle current = relative(parent, file.getFileName().toString(), DELETE | READ_ATTRIBUTES, OPEN, SHARE_ALL, false)) {
                        remove(current); return true;
                    } catch (NoSuchFileException absent) { return compare; }
                }
                // Refuse an existing reparse point/type mismatch before constructing the new credential.
                try (Handle existing = relative(parent, file.getFileName().toString(), READ_ATTRIBUTES, OPEN, SHARE_ALL, false)) { }
                catch (NoSuchFileException absent) { }
                byte[] bytes = CredentialStore.encode(next, legacyFormat);
                if (bytes.length > MAX_BYTES) throw new IOException("Credential file is too large.");
                String temporary = file.getFileName() + "." + UUID.randomUUID() + ".tmp";
                try (Handle output = relative(parent, temporary, GENERIC_WRITE | READ_ATTRIBUTES | READ_CONTROL | WRITE_DAC | DELETE,
                        CREATE, 0, false)) { // exclusive handle, protected DACL already installed atomically at creation
                    boolean moved = false;
                    try {
                        int offset = 0;
                        while (offset < bytes.length) {
                            byte[] remaining = Arrays.copyOfRange(bytes, offset, bytes.length); IntByReference written = new IntByReference();
                            if (!Api.K.WriteFile(output.value, remaining, remaining.length, written, null)) throw winError();
                            int n = written.getValue(); if (n <= 0 || n > remaining.length) throw new IOException("Credential write did not complete.");
                            offset += n;
                        }
                        if (!Api.K.FlushFileBuffers(output.value)) throw winError();
                        rename(output, parent, file.getFileName().toString()); moved = true;
                    } finally {
                        // The same handle still identifies the temp object even if somebody changed its name.
                        // After successful rename it is authoritative: never delete it as temporary cleanup.
                        if (!moved) remove(output);
                    }
                }
                return true;
            }
        }
    }
}
