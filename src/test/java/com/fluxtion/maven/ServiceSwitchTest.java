package com.fluxtion.maven;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;
public class ServiceSwitchTest {
    @Test public void switchingRequiresConfirmationAndRevokesOnlyAfterSuccessfulSave() throws Exception {
        try (StubCompilerServer old = new StubCompilerServer(); StubCompilerServer next = new StubCompilerServer()) {
            Path dir=Files.createTempDirectory("service-switch").toRealPath(); CredentialStore store=new CredentialStore(dir.resolve("credentials"));
            store.write(new CredentialStore.Stored(StubCompilerServer.TOKEN,"cred1","acct-42",old.url()));
            FluxtionLoginMojo m=new FluxtionLoginMojo(); m.env=k->null; m.compilerUrl=next.url(); m.credentialStore=store;
            m.verificationOrigin="https://login.example.com"; m.browser=false; m.setLog(new RecordingLog());
            long[] now={0}; m.clockMillis=()->now[0]; m.sleeper=ms->now[0]+=ms;
            assertThrows(org.apache.maven.plugin.MojoFailureException.class,m::execute);
            assertTrue(old.requests.isEmpty()); assertTrue(next.requests.isEmpty()); assertEquals(old.url(),store.read().url);
            m.replaceService=true; next.tokenReply(200,StubCompilerServer.success()); m.execute();
            assertEquals(next.url(),store.read().url);
            assertEquals(1,old.requests("DELETE","/v1/credentials/cred1").size());
            assertEquals(0,next.requests("DELETE","/v1/credentials/cred1").size());
            // Return to the first issuer: confirmation applies in both directions, never to a wildcard service.
            m.compilerUrl=old.url(); m.replaceService=false;
            assertThrows(org.apache.maven.plugin.MojoFailureException.class,m::execute);
            assertEquals(next.url(),store.read().url);
            m.replaceService=true; old.tokenReply(200,StubCompilerServer.success()); m.execute();
            assertEquals(old.url(),store.read().url);
            assertEquals(1,old.requests("DELETE","/v1/credentials/cred1").size());
            assertEquals(1,next.requests("DELETE","/v1/credentials/cred1").size());
            Files.delete(store.file()); Files.delete(dir.resolve("credentials.lock")); Files.delete(dir);
        }
    }
}
