package com.fluxtion.maven;

import org.junit.Test;
import org.apache.maven.execution.*;
import org.apache.maven.plugin.MojoExecutionException;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.Assert.*;

public class OriginPolicyTest {
    @Test public void equivalentCompilerSpellingsShareTheStagingPin() {
        CompilerUrl canonical=CompilerUrl.parse("https://compiler-staging.fluxtion-playground.dev");
        CompilerUrl alternative=CompilerUrl.parse("HTTPS://COMPILER-STAGING.FLUXTION-PLAYGROUND.DEV:443/");
        assertEquals(canonical.toString(),alternative.toString());
        assertEquals("https://meticulous-comfort-69-staging.authkit.app",VerificationOrigin.trusted(alternative.toString(),null));
        assertTrue(CompilerUrl.parse("http://localhost:8123").sameOrigin(CompilerUrl.parse("http://127.0.0.1:8123")));
        assertFalse(CompilerUrl.parse("http://localhost:8123").sameOrigin(CompilerUrl.parse("http://127.0.0.2:8123")));
        assertFalse(CompilerUrl.parse("http://localhost:8123").sameOrigin(CompilerUrl.parse("http://[::1]:8123")));
        assertEquals("https://localhost",CompilerUrl.parse("https://LOCALHOST:443").toString());
    }
    private static MavenSession session(Properties user,Properties system) {
        DefaultMavenExecutionRequest request=new DefaultMavenExecutionRequest();
        request.setUserProperties(user);request.setSystemProperties(system);
        return new MavenSession(null,(org.eclipse.aether.RepositorySystemSession)null,request,new DefaultMavenExecutionResult());
    }
    @Test public void pomInjectedTrustIsIgnoredButUserTrustIsHonoured() throws Exception {
        try(StubCompilerServer server=new StubCompilerServer()) {
            Path dir=Files.createTempDirectory("origin-scope");
            try {
                FluxtionLoginMojo mojo=new FluxtionLoginMojo();mojo.env=k->null;mojo.compilerUrl=server.url();
                mojo.credentialStore=new CredentialStore(dir.resolve("credentials"));mojo.browser=false;
                mojo.verificationOrigin="https://login.example.com"; // simulate POM parameter injection
                mojo.session=session(new Properties(),new Properties());
                long[] now={0};mojo.clockMillis=()->now[0];mojo.sleeper=ms->now[0]+=ms;
                RecordingLog log=new RecordingLog();mojo.setLog(log);server.tokenReply(200,StubCompilerServer.success());
                assertThrows(MojoExecutionException.class,mojo::execute);
                assertEquals(0,server.requests("POST","/auth/device/token").size());
                assertFalse(log.all().contains("\"event\":\"pending\""));
                mojo.session.getUserProperties().setProperty("fluxtion.login.verificationOrigin","https://login.example.com");
                mojo.verificationOrigin="https://ignored-project.example";
                mojo.execute();
                assertEquals("acct-42",mojo.credentialStore.read().accountId);
            } finally {
                try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
            }
        }
    }
    @Test public void nativeLoadFailureHasBuildFallbackButDiagnosticsStayStrict() throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"));
        java.net.URL classes=CredentialStore.class.getProtectionDomain().getCodeSource().getLocation();
        try(java.net.URLClassLoader isolated=new java.net.URLClassLoader(new java.net.URL[]{classes},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("com.sun.jna."))throw new ClassNotFoundException("synthetic native-library absence");
                if(name.startsWith("com.fluxtion.maven.CredentialStore")||name.startsWith("com.fluxtion.maven.MacCredentialFile")
                        ||name.startsWith("com.fluxtion.maven.CompilerCredentials")||name.equals("com.fluxtion.maven.CompilerUrl")) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
                return super.loadClass(name,resolve);
            }
        }) {
            Path dir=Files.createTempDirectory("native-unavailable");
            try {
                Class<?> storeType=isolated.loadClass("com.fluxtion.maven.CredentialStore");
                java.lang.reflect.Constructor<?> constructor=storeType.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
                Object store=constructor.newInstance(dir.resolve("credentials"));
                Class<?> policy=isolated.loadClass("com.fluxtion.maven.CompilerCredentials");
                java.lang.reflect.Method build=policy.getDeclaredMethod("resolveForBuild",java.util.function.Function.class,
                        storeType,String.class,org.apache.maven.plugin.logging.Log.class);build.setAccessible(true);
                RecordingLog log=new RecordingLog();
                Object result=build.invoke(null,(java.util.function.Function<String,String>)(k->null),store,null,log);
                java.lang.reflect.Field source=result.getClass().getDeclaredField("source");source.setAccessible(true);
                assertEquals("NONE",source.get(result).toString());
                assertTrue(log.all().contains("continuing without a stored credential"));
                java.lang.reflect.Method diagnostic=policy.getDeclaredMethod("resolve",java.util.function.Function.class,
                        storeType,String.class,org.apache.maven.plugin.logging.Log.class);diagnostic.setAccessible(true);
                java.lang.reflect.InvocationTargetException failure=assertThrows(java.lang.reflect.InvocationTargetException.class,
                        ()->diagnostic.invoke(null,(java.util.function.Function<String,String>)(k->null),store,null,log));
                assertTrue(failure.getCause() instanceof MojoExecutionException);
                assertTrue(failure.getCause().getMessage().contains("native filesystem support"));
            } finally {Files.delete(dir);}
        }
    }

    @Test public void committedMavenOptionsCannotChooseTheTrustedOrigin() throws Exception {
        try(StubCompilerServer server=new StubCompilerServer()) {
            Path dir=Files.createTempDirectory("project-trust");
            try {
                Files.createDirectories(dir.resolve(".mvn"));

                FluxtionLoginMojo mojo=new FluxtionLoginMojo();mojo.env=k->null;mojo.compilerUrl=server.url();
                mojo.credentialStore=new CredentialStore(dir.resolve("credentials"));mojo.browser=false;
                mojo.verificationOrigin="https://login.example.com";
                Properties user=new Properties();user.setProperty("fluxtion.login.verificationOrigin","https://login.example.com");
                mojo.session=session(user,new Properties());mojo.project=new org.apache.maven.project.MavenProject();
                mojo.project.setFile(dir.resolve("pom.xml").toFile());
                long[] now={0};mojo.clockMillis=()->now[0];mojo.sleeper=ms->now[0]+=ms;mojo.setLog(new RecordingLog());
                server.tokenReply(200,StubCompilerServer.success());
                for(String option:new String[]{"-Dfluxtion.login.verificationOrigin=https://login.example.com",
                        "--define=fluxtion.login.verificationOrigin=https://login.example.com",
                        "--define\nfluxtion.login.verificationOrigin=https://login.example.com",
                        "-D\nfluxtion.login.verificationOrigin=https://login.example.com", "@.mvn/trust.args"}) {
                    Files.write(dir.resolve(".mvn/maven.config"),java.util.Collections.singletonList(option));
                    assertThrows(MojoExecutionException.class,mojo::execute);
                    assertTrue(server.requests.isEmpty());
                }
            } finally {
                try(java.util.stream.Stream<Path> paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
            }
        }
    }

}
