package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Signs this machine in to the Fluxtion compiler with the device authorisation flow and stores the resulting
 * credential in {@code ~/.fluxtion/credentials} (owner-only). Interactive: refuses to run when {@code CI} is set.
 *
 * <pre>mvn com.telamin.fluxtion:fluxtion-maven-plugin:login -Dfluxtion.compilerUrl=https://compiler.example.com</pre>
 */
@Mojo(name = "login", requiresProject = false, aggregator = true, threadSafe = true)
public class FluxtionLoginMojo extends AbstractCompilerAccountMojo {

    static final String CI_REFUSAL = "fluxtion:login is interactive and does not run in CI (the CI environment"
            + " variable is set). For CI, an account administrator creates an automation credential in the"
            + " account's credentials list and stores it as the build's " + CompilerCredentials.ENV_TOKEN
            + " secret; the generation goals read it from there.";

    /** Offer to open the verification page in the system browser when a desktop is available. */
    @Parameter(property = "fluxtion.login.browser", defaultValue = "true")
    protected boolean browser = true;

    /** Waits between polls; replaced in tests. */
    CompilerAuthClient.Sleeper sleeper = Thread::sleep;

    /** Wall clock in milliseconds; replaced in tests. */
    LongSupplier clockMillis = System::currentTimeMillis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (CompilerCredentials.isCi(env)) {
            throw new MojoFailureException(CI_REFUSAL);
        }
        CredentialStore store = credentialStore();
        CredentialStore.Stored previous = readQuietly(store);
        CompilerUrl url = compilerUrl(previous == null ? null : previous.url);
        if (url == null) {
            throw new MojoFailureException("No compiler URL. Pass -Dfluxtion.compilerUrl=https://<compiler host>,"
                    + " set FLUXTION_COMPILER_URL, or set <compilerUrl> in the plugin configuration.");
        }
        CompilerAuthClient client = clientFactory.apply(url);

        CompilerAuthClient.DeviceAuthorization auth;
        try {
            auth = client.authorize();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not start sign-in at " + url + ": " + e.getMessage(), e);
        }
        getLog().info("");
        getLog().info("To sign in to the Fluxtion compiler, open:");
        getLog().info("    " + auth.verificationUri);
        getLog().info("and enter the code:");
        getLog().info("    " + auth.userCode);
        getLog().info("");
        AtomicBoolean finished = new AtomicBoolean(false);
        if (browser && browserAvailable()) {
            offerBrowser(auth.verificationUriComplete != null ? auth.verificationUriComplete : auth.verificationUri,
                    finished);
        }
        getLog().info("Waiting for approval (the code expires in " + auth.expiresInSeconds / 60
                + " minutes; Ctrl+C cancels)...");

        CompilerAuthClient.Issued issued;
        try {
            issued = client.pollForToken(auth, sleeper, clockMillis);
        } catch (CompilerAuthClient.DeviceFlowException e) {
            throw new MojoFailureException(e.getMessage());
        } catch (IOException e) {
            throw new MojoExecutionException("Sign-in failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoFailureException("Sign-in cancelled.");
        } finally {
            finished.set(true);
        }

        try {
            store.write(new CredentialStore.Stored(issued.accessToken, issued.credentialId, issued.accountId,
                    url.toString()));
        } catch (IOException e) {
            // the credential is live but unsaved: revoke it rather than leave it orphaned
            revokeQuietly(client, issued.credentialId, issued.accessToken);
            throw new MojoExecutionException("Signed in, but the credential could not be saved to " + store.file()
                    + " (" + e.getMessage() + "); it has been revoked.", e);
        }
        getLog().info("Signed in to " + url + " as account " + issued.accountId + ".");
        getLog().info("Credential saved to " + store.file() + " (readable only by you)."
                + " mvn fluxtion:logout removes and revokes it.");

        if (previous != null && previous.credentialId != null && !previous.credentialId.equals(issued.credentialId)) {
            CompilerUrl previousUrl = parseQuietly(previous.url);
            if (previousUrl != null) {
                revokeQuietly(clientFactory.apply(previousUrl), previous.credentialId, previous.token);
            }
        }

        try {
            Map<String, Object> usage = client.usage(issued.accessToken);
            Object plan = usage.get("plan");
            Object available = usage.get("available");
            getLog().info("Plan: " + (plan == null ? "unknown" : plan) + "; credits available: "
                    + (available == null ? "unknown" : available) + ".");
        } catch (IOException e) {
            getLog().warn("Signed in, but the remaining credits could not be read (" + e.getMessage() + ").");
        }
    }

    private boolean browserAvailable() {
        if (CompilerCredentials.isCi(env) || System.console() == null) return false;
        try {
            return !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Opens the browser when the person presses Enter; never automatically. Polling continues meanwhile. */
    private void offerBrowser(String uri, AtomicBoolean finished) {
        getLog().info("Press Enter to open this page in your browser.");
        Thread t = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                if (in.readLine() != null && !finished.get()) {
                    Desktop.getDesktop().browse(new URI(uri));
                }
            } catch (Exception | LinkageError e) {
                getLog().info("Could not open a browser; open the address above yourself.");
            }
        }, "fluxtion-login-browser");
        t.setDaemon(true);
        t.start();
    }

    private void revokeQuietly(CompilerAuthClient client, String credentialId, String token) {
        if (credentialId == null) return;
        try {
            client.revoke(credentialId, token);
        } catch (IOException e) {
            getLog().warn("Could not revoke credential " + credentialId + " (" + e.getMessage()
                    + "); revoke it from the account's credentials list.");
        }
    }

    private CredentialStore.Stored readQuietly(CredentialStore store) {
        try {
            return store.read();
        } catch (IOException e) {
            return null;
        }
    }

    private static CompilerUrl parseQuietly(String text) {
        try {
            return text == null ? null : CompilerUrl.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
