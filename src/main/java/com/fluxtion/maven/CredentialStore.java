package com.fluxtion.maven;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Maven-compatible single machine sign-in. All operations use no-follow directory handles. */
final class CredentialStore {
    static final String FILE_PROPERTY="fluxtion.credentialsFile";
    static final class Stored {
        final String token,credentialId,accountId,url;
        Stored(String token,String credentialId,String accountId,String url) {
            this.token=token; this.credentialId=credentialId; this.accountId=accountId; this.url=url;
        }
        @Override public String toString() { return "compiler credential (secret redacted)"; }
    }
    private final Path file;
    private final boolean legacyFormat;
    private boolean windows() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"); }
    private boolean mac() { return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"); }
    CredentialStore(Path file) { this(file, false); }
    CredentialStore(Path file, boolean legacyFormat) { this.file=file.toAbsolutePath().normalize(); this.legacyFormat=legacyFormat; }
    static CredentialStore defaultStore() {
        String override=System.getProperty(FILE_PROPERTY);
        return new CredentialStore(override!=null&&!override.trim().isEmpty() ? Paths.get(override.trim())
                : Paths.get(System.getProperty("user.home"),".fluxtion","credentials"));
    }
    Path file() { return file; }
    boolean exists() {
        if(windows()){try{return new WindowsCredentialFile(file,legacyFormat).read()!=null;}catch(IOException e){return false;}}
        if(mac()) { try { return new MacCredentialFile(file,legacyFormat).read()!=null; } catch(IOException e) {return false;} }
        try (Directory dir=directory(false)) { return regular(dir.handle,file.getFileName()); }
        catch (IOException e) { return false; }
    }
    Stored read() throws IOException {
        if(windows())return new WindowsCredentialFile(file,legacyFormat).read();
        if(mac()) return new MacCredentialFile(file,legacyFormat).read();
        try (Directory dir=directory(false)) { return read(dir.handle); }
        catch (NoSuchFileException e) { return null; }
    }
    private Stored read(SecureDirectoryStream<Path> dir) throws IOException {
        Path name=file.getFileName();
        if (!regular(dir,name)) return null;
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try (SeekableByteChannel ch=dir.newByteChannel(name,options(StandardOpenOption.READ));
             InputStream in=Channels.newInputStream(ch)) {
            byte[] data=new byte[4096]; int n;
            while ((n=in.read(data))!=-1) {
                if (bytes.size()+n>16384) throw new IOException("credential file is too large");
                bytes.write(data,0,n);
            }
        }
        return decode(bytes.toByteArray(),legacyFormat);
    }
    static Stored decode(byte[] bytes, boolean legacyFormat) throws IOException {
        try {
            if(legacyFormat) {
                String text=new String(java.util.Base64.getDecoder().decode(new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim()),java.nio.charset.StandardCharsets.UTF_8);
                Map<String,String> fields=new HashMap<>();
                for(String line:text.split("\n")){int eq=line.indexOf('=');if(eq>0)fields.put(line.substring(0,eq),line.substring(eq+1));}
                if(!"1".equals(fields.get("v")))throw new IllegalArgumentException();
                return new Stored(fields.get("bearer"),fields.get("credentialId"),fields.get("accountId"),fields.get("service"));
            }
            Properties p=new Properties();p.load(new ByteArrayInputStream(bytes));
            String token=p.getProperty("token");
            if(token==null||token.trim().isEmpty())return null;
            return new Stored(token.trim(),p.getProperty("credential_id"),p.getProperty("account_id"),p.getProperty("url"));
        } catch(IllegalArgumentException e){throw new IOException("Credential file is malformed.");}
    }
    static byte[] encode(Stored value, boolean legacyFormat) throws IOException {
        byte[] bytes;
        if(legacyFormat) {
            String text="v=1\nservice="+value.url+"\ncredentialId="+value.credentialId+"\naccountId="+value.accountId+"\nbearer="+value.token+"\n";
            bytes=java.util.Base64.getEncoder().encode(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else {
            Properties p=new Properties();p.setProperty("token",value.token);
            if(value.credentialId!=null)p.setProperty("credential_id",value.credentialId);
            if(value.accountId!=null)p.setProperty("account_id",value.accountId);
            if(value.url!=null)p.setProperty("url",value.url);
            ByteArrayOutputStream out=new ByteArrayOutputStream();p.store(out,"Fluxtion compiler credential. Do not commit or share.");bytes=out.toByteArray();
        }
        if(bytes.length>16384)throw new IOException("Credential file is too large.");
        return bytes;
    }

    boolean tooOpen() {
        if(mac()) return new MacCredentialFile(file,legacyFormat).tooOpen();
        try (Directory dir=directory(false)) {
            Set<PosixFilePermission> p=dir.handle.getFileAttributeView(file.getFileName(),PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().permissions();
            return p.stream().anyMatch(v->v.name().startsWith("GROUP_")||v.name().startsWith("OTHERS_"));
        } catch (IOException|RuntimeException e) { return false; }
    }
    void write(Stored value) throws IOException { if(windows()){new WindowsCredentialFile(file,legacyFormat).change(null,value,false);return;} if(mac()){new MacCredentialFile(file,legacyFormat).change(null,value,false);return;} locked(dir->{write(dir,value);return true;}); }
    boolean compareAndWrite(Stored expected,Stored value) throws IOException {
        if(windows())return new WindowsCredentialFile(file,legacyFormat).change(expected,value,true);
        if(mac()) return new MacCredentialFile(file,legacyFormat).change(expected,value,true);
        return locked(dir->{if(!same(read(dir),expected))return false;write(dir,value);return true;});
    }
    boolean compareAndDelete(Stored expected) throws IOException {
        if(windows())return new WindowsCredentialFile(file,legacyFormat).change(expected,null,true);
        if(mac()) return new MacCredentialFile(file,legacyFormat).change(expected,null,true);
        return locked(dir->{if(!same(read(dir),expected))return false;remove(dir);return true;});
    }
    boolean delete() throws IOException { if(windows())return new WindowsCredentialFile(file,legacyFormat).change(null,null,false); if(mac()) return new MacCredentialFile(file,legacyFormat).change(null,null,false); return locked(this::remove); }
    private boolean remove(SecureDirectoryStream<Path> dir) throws IOException {
        if(!regular(dir,file.getFileName()))return false;
        dir.deleteFile(file.getFileName());return true;
    }
    private static boolean same(Stored a,Stored b) {
        return a==b || a!=null&&b!=null&&Objects.equals(a.token,b.token)&&Objects.equals(a.url,b.url)
                &&Objects.equals(a.accountId,b.accountId)&&Objects.equals(a.credentialId,b.credentialId);
    }
    private interface Operation { boolean run(SecureDirectoryStream<Path> dir) throws IOException; }
    private boolean locked(Operation operation) throws IOException {
        synchronized(CredentialStore.class) {
            try (Directory dir=directory(true)) {
                Path lock=Paths.get(file.getFileName()+".lock");
                regular(dir.handle,lock); // absent is allowed; a link or non-regular entry is refused
                try (SeekableByteChannel channel=dir.handle.newByteChannel(lock,options(StandardOpenOption.CREATE,StandardOpenOption.WRITE),ownerFile())) {
                    if(!(channel instanceof FileChannel)) throw new IOException("secure credential locking is unavailable on this filesystem");
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    FileLock acquired;
                    while((acquired=((FileChannel)channel).tryLock())==null) {
                        if(System.nanoTime()-deadline>=0)throw new IOException("Compiler credential store is busy; retry.");
                        try {Thread.sleep(20);} catch(InterruptedException e) {
                            Thread.currentThread().interrupt();throw new IOException("Credential operation cancelled.");
                        }
                    }
                    try(FileLock held=acquired) { return operation.run(dir.handle); }
                }
            }
        }
    }
    private void write(SecureDirectoryStream<Path> dir,Stored value) throws IOException {
        regular(dir,file.getFileName());
        byte[] bytes=encode(value,legacyFormat);
        Path tmp=Paths.get(file.getFileName()+"."+UUID.randomUUID()+".tmp");
        try {
            try(SeekableByteChannel channel=dir.newByteChannel(tmp,options(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),ownerFile())) {
                ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);
                if(channel instanceof FileChannel)((FileChannel)channel).force(true);
            }
            // SecureDirectoryStream.move is descriptor-relative and atomic; it cannot traverse a replaced ancestor.
            dir.move(tmp,dir,file.getFileName());
        } finally { try { dir.deleteFile(tmp); } catch(NoSuchFileException ignored) { } }
    }
    private static FileAttribute<Set<PosixFilePermission>> ownerFile() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }
    private static Set<OpenOption> options(StandardOpenOption... values) {
        Set<OpenOption> out=new HashSet<>(Arrays.asList(values));out.add(LinkOption.NOFOLLOW_LINKS);return out;
    }
    private static boolean regular(SecureDirectoryStream<Path> dir,Path name) throws IOException {
        try {
            BasicFileAttributes a=dir.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
            if(!a.isRegularFile())throw new IOException("credential path is not a regular file (links are refused)");
            return true;
        } catch(NoSuchFileException e) {return false;}
    }
    private static final class Directory implements AutoCloseable {
        final SecureDirectoryStream<Path> handle;
        Directory(SecureDirectoryStream<Path> handle){this.handle=handle;}
        @Override public void close() throws IOException {handle.close();}
    }
    private Directory directory(boolean create) throws IOException {
        DirectoryStream<Path> root=Files.newDirectoryStream(file.getRoot());
        if(!(root instanceof SecureDirectoryStream)) {
            root.close();throw new IOException("This filesystem cannot securely share compiler credentials; no credential changed.");
        }
        SecureDirectoryStream<Path> current=(SecureDirectoryStream<Path>)root;
        Path absolute=file.getRoot();
        try {
            for(Path part:file.getParent()) {
                absolute=absolute.resolve(part);
                SecureDirectoryStream<Path> next;
                try {next=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);}
                catch(NoSuchFileException e) {
                    if(!create)throw e;
                    // mkdir has no descriptor-relative Java API. Only an empty owner-only directory is created here;
                    // the subsequent no-follow handle lookup must succeed before any lock or credential is written.
                    try {Files.createDirectory(absolute,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
                    catch(FileAlreadyExistsException ignored) { }
                    next=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);
                }
                current.close();current=next;
            }
            return new Directory(current);
        } catch(IOException|RuntimeException e) {current.close();throw e;}
    }
}
