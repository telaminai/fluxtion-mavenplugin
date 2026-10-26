package com.fluxtion.maven;

import org.junit.Test;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import java.nio.file.*;
import static org.junit.Assert.*;

public class IndependentLoginEvidenceTest {
    private FluxtionLoginMojo login(StubCompilerServer server, CredentialStore store) {
        FluxtionLoginMojo mojo = new FluxtionLoginMojo();
        mojo.env = key -> null;
        mojo.credentialStore = store;
        mojo.compilerUrl = server.url();
        mojo.browser = false;
        long[] now = {0};
        mojo.clockMillis = () -> now[0];
        mojo.sleeper = millis -> now[0] += millis;
        mojo.setLog(new RecordingLog());
        return mojo;
    }
    @Test public void failedRevokeMustNotBeReportedAsRevoked() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            server.tokenReply(200, StubCompilerServer.success());
            server.revokeStatus = 500;
            Path blockingFile = Files.createTempFile("review-not-directory", ".tmp");
            CredentialStore store = new CredentialStore(blockingFile.resolve("credentials"));
            try {
                login(server, store).execute();
                fail("writing beneath a regular file must fail");
            } catch (MojoExecutionException expected) {
                assertEquals(1, server.requests("DELETE", "/v1/credentials/cred1").size());
                assertFalse("a 500 proves no revocation: " + expected.getMessage(), expected.getMessage().contains("it has been revoked"));
                assertTrue(expected.getMessage().contains("revocation is unconfirmed"));
                assertTrue(expected.getMessage().contains("cred1"));
                assertFalse(expected.getMessage().contains(StubCompilerServer.TOKEN));
            } finally { Files.deleteIfExists(blockingFile); }
        }
    }
    @Test public void unknownErrorMustNotEchoTheDeviceSecret() throws Exception {
        try (StubCompilerServer server = new StubCompilerServer()) {
            server.tokenReply(400, "{\"error\":\"" + StubCompilerServer.DEVICE_CODE + "\"}");
            Path dir = Files.createTempDirectory("review-device-log");
            try {
                login(server, new CredentialStore(dir.resolve("credential"))).execute();
                fail("unknown error must fail sign-in");
            } catch (MojoFailureException expected) {
                assertFalse("device secret must not reach Maven's failure message", expected.getMessage().contains(StubCompilerServer.DEVICE_CODE));
                assertTrue(expected.getMessage().contains("HTTP 400"));
            } finally { Files.deleteIfExists(dir); }
        }
    }

    @Test public void cleanupClaimsSuccessOnlyForAcknowledgedOrAbsentCredentials() throws Exception {
        for (int status : new int[]{200, 204, 404, 401, 429, 500}) {
            try (StubCompilerServer server = new StubCompilerServer()) {
                server.tokenReply(200, StubCompilerServer.success());
                server.revokeStatus = status;
                Path blockingFile = Files.createTempFile("review-cleanup-status", ".tmp");
                try {
                    login(server, new CredentialStore(blockingFile.resolve("credentials"))).execute();
                    fail("credential storage must fail");
                } catch (MojoExecutionException expected) {
                    boolean confirmed = status == 200 || status == 204 || status == 404;
                    assertEquals("cleanup status " + status, confirmed,
                            expected.getMessage().contains("revoked or is already absent"));
                    assertEquals("cleanup status " + status, !confirmed,
                            expected.getMessage().contains("revocation is unconfirmed"));
                    assertFalse(expected.getMessage().contains(StubCompilerServer.TOKEN));
                } finally { Files.deleteIfExists(blockingFile); }
            }
        }
    }

    @Test public void errorAndMessageFieldsAreNeverEchoedByOtherAccountRequests() {
        for (int status : new int[]{400, 401, 429, 500}) {
            String body = "{\"error\":" + Json.quote(StubCompilerServer.DEVICE_CODE)
                    + ",\"message\":" + Json.quote(StubCompilerServer.TOKEN) + "}";
            assertEquals("HTTP " + status,
                    CompilerAuthClient.describe(new CompilerAuthClient.Response(status, body)));
        }
    }
}
