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
 * <pre>mvn com.telamin.fluxtion:fluxtion-maven-plugin:login</pre>
 *
 * signs in to the managed compiler; {@code -Dfluxtion.compilerUrl=https://compiler.example.com} (or
 * {@code FLUXTION_COMPILER_URL}) signs in to another.
 */
@Mojo(name = "login", requiresProject = false, aggregator = true, threadSafe = true)
public class FluxtionLoginMojo extends AbstractCompilerAccountMojo {

    static final String CI_REFUSAL = "fluxtion:login is interactive and does not run in CI (a CI environment"
            + " variable such as CI or JENKINS_URL is set). For CI, an account administrator creates an automation credential in the"
            + " account's credentials list and stores it as the build's " + CompilerCredentials.ENV_TOKEN
            + " secret; the generation goals read it from there.";

    /** Offer to open the verification page in the system browser when a desktop is available. */
    @Parameter(property = "fluxtion.login.browser", defaultValue = "true")
    protected boolean browser = true;

    @Parameter(property = "fluxtion.login.force", defaultValue = "false")
    protected boolean force;

    /** Test injection only. Maven trusts only session user/JVM properties, never this POM-configurable field. */
    protected String verificationOrigin;

    private String trustedOriginOverride() throws MojoExecutionException {
        refuseProjectTrustOptions();
        if(session==null)return verificationOrigin; // direct unit invocation, not a Maven execution
        String user=session.getUserProperties().getProperty("fluxtion.login.verificationOrigin");
        if(user!=null&&!user.trim().isEmpty())return user;
        return session.getSystemProperties().getProperty("fluxtion.login.verificationOrigin");
    }

    private void refuseProjectTrustOptions() throws MojoExecutionException {
        java.io.File base=project==null?null:project.getBasedir();
        if(base==null&&session!=null&&session.getRequest().getBaseDirectory()!=null)
            base=new java.io.File(session.getRequest().getBaseDirectory());
        if(base==null)return;
        for(java.nio.file.Path directory=base.toPath().toAbsolutePath();directory!=null;directory=directory.getParent()) {
            java.nio.file.Path mvn=directory.resolve(".mvn");
            if(!java.nio.file.Files.isDirectory(mvn))continue;
            for(String name:new String[]{"maven.config","jvm.config"}) {
                java.nio.file.Path path=mvn.resolve(name);
                if(!java.nio.file.Files.exists(path))continue;
                try {
                    if(java.nio.file.Files.size(path)>65536)throw new IOException("configuration too large");
                    String text=new String(java.nio.file.Files.readAllBytes(path),StandardCharsets.UTF_8);
                    // Maven has several define spellings and JVM @argfiles can hide another definition.
                    // Refuse conservatively, including comments, rather than reimplement either argument parser.
                    if(text.contains("fluxtion.login.verificationOrigin")||text.contains("@"))
                        throw new MojoExecutionException("Interactive sign-in refuses project .mvn references to the trusted verification origin or @argfiles. "
                                + "Remove that project setting and set -Dfluxtion.login.verificationOrigin explicitly in your terminal or user JVM options. No request sent.");
                } catch(IOException e) {
                    throw new MojoExecutionException("Could not check project Maven options for verification-origin overrides. No request sent.");
                }
            }
            return;
        }
    }

    @Parameter(property = "fluxtion.login.replaceService", defaultValue = "false")
    protected boolean replaceService;

    private AuthRecords records;

    /** Waits between polls; replaced in tests. */
    CompilerAuthClient.Sleeper sleeper = Thread::sleep;

    /** Wall clock in milliseconds; replaced in tests. */
    LongSupplier clockMillis = System::currentTimeMillis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        terminalEmitted.set(false);
        records = new AuthRecords(getLog());
        Thread cancellation = new Thread(() -> emit("cancelled", "unknown", null, null, null), "fluxtion-login-cancel");
        Runtime.getRuntime().addShutdownHook(cancellation);
        try {
            executeLogin();
        } catch (MojoFailureException | MojoExecutionException e) {
            // Specific device outcomes are emitted at their transition. Unclassified failures remain unknown.
            if (!terminalEmitted.get()) emit("service_error", "unknown", null, null, null);
            throw e;
        } catch (RuntimeException e) {
            if (!terminalEmitted.get()) emit("service_error", "unknown", null, null, null);
            throw new MojoExecutionException("Compiler sign-in failed before completion.");
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(cancellation); } catch (IllegalStateException ignored) { }
        }
    }

    private final AtomicBoolean terminalEmitted = new AtomicBoolean();
    private void emit(String event, String identity, String source, String account, Map<String, Object> extra) {
        if (!"pending".equals(event) && !terminalEmitted.compareAndSet(false, true)) return;
        records.emit(event, identity, source, account, extra);
    }

    private void executeLogin() throws MojoExecutionException, MojoFailureException {
        if (CompilerCredentials.isCi(env)) {
            emit("usage_error", "unknown", null, null, null);
            throw new MojoFailureException(CI_REFUSAL);
        }
        String envToken = env.apply(CompilerCredentials.ENV_TOKEN);
        if (envToken != null && !envToken.trim().isEmpty()) {
            emit("usage_error", "unknown", "environment", null, null);
            throw new MojoFailureException("FLUXTION_API_TOKEN overrides saved sign-in. Use the account goal to diagnose it; login does not change it.");
        }
        checkExecutionUrls();
        CredentialStore store = credentialStore();
        CredentialStore.Stored previous;
        try { previous = store.read(); }
        catch (IOException e) { throw new MojoExecutionException("Stored sign-in could not be read; it has not been changed."); }
        CompilerUrl url = compilerUrl(previous == null ? CompilerCredentials.DEFAULT_COMPILER_URL : previous.url);
        records.compiler = url.toString();
        if (previous != null && !url.sameOrigin(parseQuietly(previous.url)) && !replaceService) {
            emit("usage_error", "unknown", "stored", null, null);
            throw new MojoFailureException("Switching compiler service replaces and revokes the previous machine sign-in. "
                    + "Confirm with -Dfluxtion.login.replaceService=true. No request sent.");
        }
        CompilerAuthClient client = clientFactory.apply(url);
        if (!force && previous != null && url.sameOrigin(parseQuietly(previous.url))) {
            try {
                String account = verifiedAccount(client, previous.token);
                emit("signed_in", "verified", "stored", account, null);
                getLog().info("Already signed in; server verified account " + account + " at " + url + ".");
                return;
            } catch (CompilerAuthClient.CredentialRejected ignored) {
                // A revoked credential is not a sign-in. Start the approval flow below.
            } catch (IOException e) {
                throw new MojoExecutionException("Could not verify existing sign-in; service unavailable or identity invalid. No new login started.");
            }
        }

        String trustedVerificationOrigin = VerificationOrigin.trusted(url.toString(), trustedOriginOverride());
        CompilerAuthClient.DeviceAuthorization auth;
        try {
            auth = client.authorize();
            VerificationOrigin.check(auth.verificationUri, trustedVerificationOrigin);
            if (auth.verificationUriComplete != null) VerificationOrigin.check(auth.verificationUriComplete, trustedVerificationOrigin);
            if (auth.userCode == null || !auth.userCode.matches("[A-Za-z0-9 -]{1,32}") || auth.expiresInSeconds <= 0 || auth.expiresInSeconds > 1800)
                throw new IOException("Invalid sign-in code or expiry.");
        } catch (IOException e) {
            throw new MojoExecutionException("Could not start sign-in at " + url + ": " + e.getMessage(), e);
        }
        Map<String, Object> pending = new java.util.LinkedHashMap<>();
        pending.put("verificationUri", auth.verificationUriComplete != null ? auth.verificationUriComplete : auth.verificationUri);
        pending.put("userCode", auth.userCode); pending.put("expiresIn", auth.expiresInSeconds);
        emit("pending", "unknown", "stored", null, pending);
        getLog().info("");
        getLog().info("To sign in to the Fluxtion compiler, open:");
        getLog().info("    " + auth.verificationUri);
        getLog().info("and enter the code:");
        getLog().info("    " + auth.userCode);
        getLog().info("");
        AtomicBoolean finished = new AtomicBoolean(false);
        URI page = CompilerAuthClient.browsable(
                auth.verificationUriComplete != null ? auth.verificationUriComplete : auth.verificationUri);
        if (browser && page != null && browserAvailable()) {
            offerBrowser(page, finished);
        }
        getLog().info("Waiting for approval (the code expires in " + auth.expiresInSeconds / 60
                + " minutes; Ctrl+C cancels)...");

        CompilerAuthClient.Issued issued;
        try {
            issued = client.pollForToken(auth, sleeper, clockMillis);
        } catch (CompilerAuthClient.DeviceFlowException e) {
            emit(e.reason == CompilerAuthClient.DeviceFlowException.Reason.DENIED ? "denied"
                    : e.reason == CompilerAuthClient.DeviceFlowException.Reason.EXPIRED ? "expired" : "service_error",
                    "unknown", "stored", null, null);
            throw new MojoFailureException(e.getMessage());
        } catch (IOException e) {
            throw new MojoExecutionException("Sign-in failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emit("cancelled", "unknown", "stored", null, null);
            throw new MojoFailureException("Sign-in cancelled.");
        } finally {
            finished.set(true);
        }

        String verified;
        try {
            verified = verifiedAccount(client, issued.accessToken);
            if (!verified.equals(issued.accountId)) throw new IOException("issued account does not match verified identity");
        } catch (IOException e) {
            revokeQuietly(client, issued.credentialId, issued.accessToken);
            throw new MojoExecutionException("Approval completed but account identity could not be verified; credential not saved.");
        }
        try {
            if (!store.compareAndWrite(previous, new CredentialStore.Stored(issued.accessToken, issued.credentialId,
                    issued.accountId, url.toString()))) throw new IOException("another sign-in changed the store; retry explicitly");
        } catch (IOException e) {
            // the credential is live but unsaved: revoke it rather than leave it orphaned
            boolean revoked = revokeQuietly(client, issued.credentialId, issued.accessToken);
            throw new MojoExecutionException("Signed in, but the credential could not be saved to " + store.file()
                    + " (" + e.getMessage() + "); " + (revoked ? "it has been revoked or is already absent."
                    : "revocation is unconfirmed. Revoke credential "
                    + (issued.credentialId == null ? "(id unavailable)" : CompilerAuthClient.printable(issued.credentialId))
                    + " from the account's credentials list."), e);
        }
        emit("signed_in", "verified", "stored", verified, null);
        getLog().info("Signed in to " + url + " as account " + CompilerAuthClient.printable(issued.accountId) + ".");
        getLog().info("Credential saved to " + store.file() + " (readable only by you)."
                + " mvn fluxtion:logout removes and revokes it.");

        if (previous != null && previous.credentialId != null && (!previous.credentialId.equals(issued.credentialId)
                || !url.sameOrigin(parseQuietly(previous.url)))) {
            CompilerUrl previousUrl = parseQuietly(previous.url);
            if (previousUrl != null) {
                revokeQuietly(clientFactory.apply(previousUrl), previous.credentialId, previous.token);
            }
        }

        try {
            Map<String, Object> usage = client.usage(issued.accessToken);
            for (String line : UsageSummary.lines(usage)) {
                getLog().info(line);
            }
        } catch (IOException e) {
            getLog().warn("Signed in, but the remaining credits could not be read (" + e.getMessage() + ").");
        }
    }

    private String verifiedAccount(CompilerAuthClient client, String token) throws IOException {
        String account = Json.str(client.me(token), "selectedAccount");
        if (account == null || !account.matches("[A-Za-z0-9_:.-]{3,128}"))
            throw new IOException("invalid account identity");
        return account;
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
    private void offerBrowser(URI uri, AtomicBoolean finished) {
        getLog().info("Press Enter to open this page in your browser.");
        Thread t = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                if (in.readLine() != null && !finished.get()) {
                    Desktop.getDesktop().browse(uri);
                }
            } catch (Exception | LinkageError e) {
                getLog().info("Could not open a browser; open the address above yourself.");
            }
        }, "fluxtion-login-browser");
        t.setDaemon(true);
        t.start();
    }

    private boolean revokeQuietly(CompilerAuthClient client, String credentialId, String token) {
        if (credentialId == null) return false;
        try {
            int status = client.revoke(credentialId, token);
            if (status == 200 || status == 204 || status == 404) return true;
            getLog().warn("Could not confirm revocation of credential " + CompilerAuthClient.printable(credentialId)
                    + " (HTTP " + status + "); revoke it from the account's credentials list.");
        } catch (IOException e) {
            getLog().warn("Could not confirm revocation of credential " + CompilerAuthClient.printable(credentialId)
                    + " after a connection failure; revoke it from the account's credentials list.");
        }
        return false;
    }

    private static CompilerUrl parseQuietly(String text) {
        try {
            return text == null ? null : CompilerUrl.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
