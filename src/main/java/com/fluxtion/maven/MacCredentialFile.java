package com.fluxtion.maven;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Darwin descriptor-relative adapter: the macOS JDK does not implement SecureDirectoryStream. */
final class MacCredentialFile {
    interface LibC extends Library {
        int open(String path,int flags,Object... mode);
        int openat(int dir,String path,int flags,Object... mode);
        int mkdirat(int dir,String path,int mode);
        int renameat(int from,String old,int to,String next);
        int unlinkat(int dir,String name,int flags);
        int close(int fd);
        int flock(int fd,int operation);
        int fsync(int fd);
        int fstat64(int fd,Pointer buffer);
        NativeLong read(int fd,byte[] data,NativeLong size);
        NativeLong write(int fd,byte[] data,NativeLong size);
    }
    private static final LibC C=Native.load("c",LibC.class,Collections.singletonMap(Library.OPTION_STRING_ENCODING,"UTF-8"));
    private static final int NOFOLLOW=0x100, DIRECTORY=0x100000, CLOEXEC=0x1000000, CREATE=0x200, EXCL=0x800, NONBLOCK=4;
    private final Path file;
    private final boolean legacyFormat;
    MacCredentialFile(Path file){this(file,false);}
    MacCredentialFile(Path file,boolean legacyFormat){this.file=file;this.legacyFormat=legacyFormat;}
    private static IOException error(){return error(Native.getLastError());}
    private static IOException error(int code){return new IOException("Secure credential operation failed (OS error " + code+").");}
    private int directory(boolean create) throws IOException {
        int current=C.open("/",DIRECTORY|NOFOLLOW|CLOEXEC);
        if(current<0)throw error();
        try {
            for(Path part:file.getParent()) {
                String name=part.toString();int next=C.openat(current,name,DIRECTORY|NOFOLLOW|CLOEXEC);
                if(next<0 && Native.getLastError()==2 && create) {
                    if(C.mkdirat(current,name,0700)!=0 && Native.getLastError()!=17)throw error();
                    next=C.openat(current,name,DIRECTORY|NOFOLLOW|CLOEXEC);
                }
                if(next<0) { if(Native.getLastError()==2)throw new NoSuchFileException("credential directory");throw error(); }
                C.close(current);current=next;
            }
            int result=current;current=-1;return result;
        } finally {if(current>=0)C.close(current);}
    }
    private static int regular(int fd) throws IOException {
        try(Memory stat=new Memory(256)) {
            // Darwin struct stat64 starts dev_t (32 bits), then mode_t (16 bits), on arm64 and x86_64.
            if(C.fstat64(fd,stat)!=0)throw error();
            int mode=stat.getShort(4)&0xffff;
            if((mode&0170000)!=0100000)throw new IOException("Credential path is not a regular file.");
            return mode;
        }
    }
    private int openFile(int dir,String name,int flags,boolean absent) throws IOException {
        int fd=C.openat(dir,name,flags|NOFOLLOW|CLOEXEC|NONBLOCK,0600);
        if(fd<0){if(absent&&Native.getLastError()==2)return -1;throw error();}
        try {regular(fd);return fd;} catch(IOException e){C.close(fd);throw e;}
    }
    /** APFS can transiently return ENOENT when two processes create this same no-follow lock entry. */
    private int openLock(int dir) throws IOException {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new IOException("Credential operation cancelled.");
            int fd=C.openat(dir,file.getFileName()+".lock",CREATE|2|NOFOLLOW|CLOEXEC|NONBLOCK,0600);
            if(fd>=0) {
                try {regular(fd);return fd;}catch(IOException e){C.close(fd);throw e;}
            }
            int code=Native.getLastError();
            if(code!=2)throw error(code);
            if(System.nanoTime()-deadline>=0)throw new IOException("Compiler credential lock could not be created; retry.");
            try {Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Credential operation cancelled.");}
        }
    }
    CredentialStore.Stored read() throws IOException {
        int dir;
        try {dir=directory(false);} catch(NoSuchFileException e){return null;}
        try{return read(dir);}finally{C.close(dir);}
    }
    private CredentialStore.Stored read(int dir) throws IOException {
        int fd=openFile(dir,file.getFileName().toString(),0,true);if(fd<0)return null;
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];
            while(true) {
                long n=C.read(fd,b,new NativeLong(b.length)).longValue();if(n<0)throw error();if(n==0)break;
                if(out.size()+n>16384)throw new IOException("Credential file is too large.");out.write(b,0,(int)n);
            }
            return CredentialStore.decode(out.toByteArray(),legacyFormat);
        }finally{C.close(fd);}
    }
    private static boolean same(CredentialStore.Stored a,CredentialStore.Stored b){return a==b||a!=null&&b!=null&&Objects.equals(a.token,b.token)&&Objects.equals(a.url,b.url)&&Objects.equals(a.credentialId,b.credentialId)&&Objects.equals(a.accountId,b.accountId);}
    boolean change(CredentialStore.Stored expected,CredentialStore.Stored replacement,boolean compare) throws IOException {
        synchronized(MacCredentialFile.class) {
            int dir=directory(true),lock=-1;
            try {
                lock=openLock(dir);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(C.flock(lock,2|4)!=0){
                    if(Native.getLastError()!=35)throw error(); // EWOULDBLOCK on Darwin
                    if(System.nanoTime()-deadline>=0)throw new IOException("Compiler credential store is busy; retry.");
                    try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Credential operation cancelled.");}
                }
                if(compare&&!same(read(dir),expected))return false;
                if(replacement==null){int fd=openFile(dir,file.getFileName().toString(),0,true);if(fd<0)return compare;C.close(fd);if(C.unlinkat(dir,file.getFileName().toString(),0)!=0)throw error();return true;}
                int existing=openFile(dir,file.getFileName().toString(),0,true);if(existing>=0)C.close(existing);
                String tmp=file.getFileName()+"."+UUID.randomUUID()+".tmp";
                int fd=openFile(dir,tmp,CREATE|EXCL|1,false);
                try {
                    byte[] bytes=CredentialStore.encode(replacement,legacyFormat);int offset=0;
                    while(offset<bytes.length){byte[] part=Arrays.copyOfRange(bytes,offset,bytes.length);long n=C.write(fd,part,new NativeLong(part.length)).longValue();if(n<=0)throw error();offset+=(int)n;}
                    if(C.fsync(fd)!=0)throw error();
                    if(C.renameat(dir,tmp,dir,file.getFileName().toString())!=0)throw error();
                }finally{C.close(fd);C.unlinkat(dir,tmp,0);}
                return true;
            }finally{if(lock>=0)C.close(lock);C.close(dir);}
        }
    }
    boolean tooOpen(){int dir=-1,fd=-1;try{dir=directory(false);fd=openFile(dir,file.getFileName().toString(),0,true);return fd>=0&&(regular(fd)&0077)!=0;}catch(IOException e){return false;}finally{if(fd>=0)C.close(fd);if(dir>=0)C.close(dir);}}
}
