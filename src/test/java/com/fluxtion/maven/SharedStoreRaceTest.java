package com.fluxtion.maven;

import org.junit.Test;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class SharedStoreRaceTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "hold".equals(args[1])) {
            try (java.nio.channels.FileChannel c=java.nio.channels.FileChannel.open(Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 java.nio.channels.FileLock lock=c.lock()) {
                System.out.println("ready"); System.out.flush(); System.in.read();
            }
            return;
        }
        CredentialStore s = new CredentialStore(Paths.get(args[0]));
        boolean won = s.compareAndWrite(null,new CredentialStore.Stored("synthetic-" + args[1],args[1],"acct-demo","https://compiler.example.com"));
        System.out.println(won ? "won" : "retained");
    }
    @Test public void twoProcessesCannotBothReplaceTheSameSnapshot() throws Exception {
        Path dir=Files.createTempDirectory("store-race").toRealPath(); Path file=dir.resolve("credentials");
        String java=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        Process a=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),getClass().getName(),file.toString(),"a").start();
        Process b=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),getClass().getName(),file.toString(),"b").start();
        try {
            assertTrue(a.waitFor(15,TimeUnit.SECONDS)); assertTrue(b.waitFor(15,TimeUnit.SECONDS));
            assertEquals(0,a.exitValue()); assertEquals(0,b.exitValue());
            String x=new String(a.getInputStream().readAllBytes()).trim(); String y=new String(b.getInputStream().readAllBytes()).trim();
            assertTrue((x.equals("won") && y.equals("retained")) || (y.equals("won") && x.equals("retained")));
            CredentialStore s=new CredentialStore(file); CredentialStore.Stored old=s.read();
            s.write(new CredentialStore.Stored("synthetic-new","new","acct-demo","https://staging.example.com"));
            assertFalse(s.compareAndDelete(old)); assertFalse(s.compareAndWrite(old,old));
            assertEquals("new",s.read().credentialId);
        } finally { a.destroyForcibly(); b.destroyForcibly(); Files.deleteIfExists(file); Files.deleteIfExists(dir.resolve("credentials.lock")); Files.delete(dir); }
    }
    @Test public void parentSubstitutionNeverReadsOutsideCredential() throws Exception {
        Path root=Files.createTempDirectory("store-parent-race").toRealPath();
        Path safe=Files.createDirectory(root.resolve("safe")), held=root.resolve("held"), outside=Files.createDirectory(root.resolve("outside"));
        CredentialStore store=new CredentialStore(safe.resolve("credentials"));
        store.write(new CredentialStore.Stored("inside","i","acct-i","https://compiler.example.com"));
        new CredentialStore(outside.resolve("credentials")).write(new CredentialStore.Stored("outside","o","acct-o","https://compiler.example.com"));
        java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Exception> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread attacker=new Thread(()->{while(!done.get())try {
            Files.move(safe,held); Files.createSymbolicLink(safe,outside);
            Thread.yield(); Files.delete(safe); Files.move(held,safe);
        }catch(Exception e){failure.set(e);break;}});
        attacker.start();
        try { for(int i=0;i<2000;i++)try {
            CredentialStore.Stored value=store.read();
            if(value!=null)assertEquals("inside",value.token);
        }catch(java.io.IOException expected) { }
        } finally {
            done.set(true); attacker.join(5000); assertFalse(attacker.isAlive());
            try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        }
        assertNull(failure.get());
    }
    @Test public void symlinkAndDanglingParentsAreNotFollowed() throws Exception {
        Path dir=Files.createTempDirectory("store-links").toRealPath(); Path link=dir.resolve("link");
        Files.createSymbolicLink(link,dir.resolve("absent")); CredentialStore s=new CredentialStore(link.resolve("credentials"));
        assertThrows(java.io.IOException.class,s::read); assertThrows(java.io.IOException.class,s::delete);
        assertThrows(java.io.IOException.class,()->s.write(new CredentialStore.Stored("synthetic","id","acct-demo","https://compiler.example.com")));
        Files.delete(link); Files.delete(dir);
    }
    @Test public void linuxLockContentionHasBoundedWait() throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"));
        Path dir=Files.createTempDirectory("store-lock-timeout").toRealPath();
        Path file=dir.resolve("credentials"), lock=dir.resolve("credentials.lock");
        String javaCommand=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(javaCommand,"-cp",System.getProperty("java.class.path"),getClass().getName(),lock.toString(),"hold").start();
        try {
            String ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try {return new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine();}
                catch(java.io.IOException e){throw new RuntimeException(e);}
            }).get(5,TimeUnit.SECONDS);
            assertEquals("ready",ready);
            long start=System.nanoTime();
            java.io.IOException failure=assertThrows(java.io.IOException.class,()->new CredentialStore(file).write(
                    new CredentialStore.Stored("synthetic","id","acct-demo","https://compiler.example.com")));
            assertTrue(failure.getMessage().contains("busy"));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<8);
            assertFalse(Files.exists(file));
        } finally {child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);Files.deleteIfExists(lock);Files.delete(dir);}
    }

}
