package com.fluxtion.maven;

import org.junit.Test;
import org.junit.Assume;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises macOS's concurrent O_CREAT|O_NOFOLLOW lock-file creation, not just flock after creation. */
public class MacLockCreationRaceTest {
    public static void main(String[] args) throws Exception {
        CredentialStore store=new CredentialStore(Paths.get(args[0]));
        store.read(); // load the native adapter before the start barrier, while the lock is still absent
        System.out.println("ready");System.out.flush();
        if(System.in.read()<0)throw new IOException("No test start signal");
        boolean saved=store.compareAndWrite(null,new CredentialStore.Stored("synthetic-"+args[1],args[1],"acct-test","https://compiler.example.com"));
        System.out.println(saved?"won":"retained");
    }
    @Test public void concurrentFirstWritersSurviveTransientMissingLockEntry() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("mac"));
        String javaCommand=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        for(int round=0;round<24;round++) {
            Path root=Files.createTempDirectory("mac-lock-create-");
            Process[] children=new Process[2]; BufferedReader[] replies=new BufferedReader[2];
            try {
                for(int i=0;i<2;i++) {
                    children[i]=new ProcessBuilder(javaCommand,"-cp",System.getProperty("java.class.path"),
                            getClass().getName(),root.resolve("credentials").toString(),String.valueOf(i)).start();
                    replies[i]=new BufferedReader(new InputStreamReader(children[i].getInputStream()));
                }
                for(BufferedReader reply:replies)assertEquals("ready",CompletableFuture.supplyAsync(()->{
                    try{return reply.readLine();}catch(IOException e){throw new UncheckedIOException(e);}
                }).get(10,TimeUnit.SECONDS));
                // Both stores have resolved their parent; release both writers onto the initially absent lock.
                for(Process child:children){child.getOutputStream().write(1);child.getOutputStream().flush();}
                for(Process child:children) {
                    assertTrue("child did not finish",child.waitFor(10,TimeUnit.SECONDS));
                    String error=new BufferedReader(new InputStreamReader(child.getErrorStream())).lines().collect(java.util.stream.Collectors.joining("\n"));
                    assertEquals("round "+round+": "+error,0,child.exitValue());
                }
                String a=replies[0].readLine(),b=replies[1].readLine();
                assertTrue("one writer must win",("won".equals(a)&&"retained".equals(b))||("retained".equals(a)&&"won".equals(b)));
            } finally {
                for(Process child:children)if(child!=null){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}
                try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                    for(Path path:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
                }
            }
        }
    }
}
