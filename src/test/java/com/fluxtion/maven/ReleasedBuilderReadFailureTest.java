package com.fluxtion.maven;

import org.junit.Test;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.Assert.*;

/** Opt-in witness against an actual published builder; does not compile or call a hosted service. */
public class ReleasedBuilderReadFailureTest {
    @Test public void refusedStoreCannotBeRediscoveredByTheReleasedBuilder() throws Exception {
        String builder=System.getProperty("fluxtion.review.builderJar"),api=System.getProperty("fluxtion.review.builderApiJar");
        org.junit.Assume.assumeNotNull(builder,api);
        org.junit.Assume.assumeTrue(System.getenv("FLUXTION_API_TOKEN")==null);
        Path dir=Files.createTempDirectory("builder-refused-store");
        String[] keys={"fluxtion.credentialsFile","fluxtion.remote.token","fluxtion.api.token","fluxtion.remote.auth"};
        Map<String,String> original=new HashMap<>();for(String key:keys)original.put(key,System.getProperty(key));
        try(java.net.URLClassLoader loader=new java.net.URLClassLoader(new URL[]{Paths.get(builder).toUri().toURL(),Paths.get(api).toUri().toURL()},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("com.telamin.fluxtion.builder.")) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Path outside=dir.resolve("other-credential"),link=dir.resolve("credentials");
            Files.write(outside,Arrays.asList("token=synthetic-refused-file","url=http://127.0.0.1:1"));
            Files.createSymbolicLink(link,outside);
            System.setProperty("fluxtion.credentialsFile",link.toString());
            System.setProperty("fluxtion.remote.token","synthetic-explicit-token");
            System.setProperty("fluxtion.api.token","synthetic-explicit-alias");
            System.clearProperty("fluxtion.remote.auth");
            CompilerCredentials.Resolved resolved=CompilerCredentials.resolveForBuild(k->null,new CredentialStore(link),null,new RecordingLog());
            Map<String,String> saved=CompilerCredentials.apply(resolved,new RecordingLog());
            try {
                Class<?> signIn=loader.loadClass("com.telamin.fluxtion.builder.compile.generation.remote.client.CompilerSignIn");
                assertEquals(false,signIn.getMethod("configured").invoke(null));
                java.lang.reflect.Method find=signIn.getDeclaredMethod("find",Function.class);find.setAccessible(true);
                assertNull(find.invoke(null,(Function<String,String>)(k->null)));
                java.lang.reflect.InvocationTargetException error=assertThrows(java.lang.reflect.InvocationTargetException.class,
                        ()->signIn.getMethod("require",String.class).invoke(null,"generate-source"));
                assertTrue(error.getCause().getMessage(),error.getCause().getMessage().contains("No compiler sign-in found"));
            } finally {CompilerCredentials.restore(saved);}
            assertEquals(link.toString(),System.getProperty("fluxtion.credentialsFile"));
            assertEquals("synthetic-explicit-token",System.getProperty("fluxtion.remote.token"));
            assertEquals("synthetic-explicit-alias",System.getProperty("fluxtion.api.token"));
        } finally {
            for(String key:keys){String value=original.get(key);if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
            try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}
        }
    }
}
