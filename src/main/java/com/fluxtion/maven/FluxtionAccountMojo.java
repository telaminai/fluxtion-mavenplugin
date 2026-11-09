package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import java.io.IOException;
import java.util.Map;

/** Read-only identity and effective allowance for the Maven build's compiler credential. */
@Mojo(name = "account", requiresProject = false, threadSafe = true, aggregator = true)
public final class FluxtionAccountMojo extends AbstractCompilerAccountMojo {
    @Override
    public void execute() throws MojoExecutionException {
        if ("session".equalsIgnoreCase(System.getProperty("fluxtion.remote.auth", "").trim()))
            throw new MojoExecutionException("The account goal checks token-mode Maven builds. Session-mode builds use the browser account.");
        checkExecutionUrls();
        CompilerCredentials.Resolved resolved = CompilerCredentials.resolve(env, credentialStore(),
                CompilerCredentials.configuredUrl(compilerUrl, env), getLog());
        if (resolved.source == CompilerCredentials.Source.NONE)
            throw new MojoExecutionException("No compiler sign-in. Run mvn fluxtion:login, or configure FLUXTION_API_TOKEN in CI. No request sent.");
        CompilerAuthClient client = clientFactory.apply(resolved.url);
        getLog().info("Compiler service: " + resolved.url);
        getLog().info("Credential source: " + (resolved.source == CompilerCredentials.Source.ENVIRONMENT
                ? "FLUXTION_API_TOKEN" : "saved Maven sign-in"));
        final String account;
        try {
            Map<String, Object> me = client.me(resolved.token);
            account = Json.str(me, "selectedAccount");
            if (account == null || !account.matches("[A-Za-z0-9_:.-]{3,128}"))
                throw new IOException("the service did not report a valid account ID");
        } catch (IOException e) {
            throw new MojoExecutionException("Could not verify compiler account: " + e.getMessage());
        }
        getLog().info("Account ID (server verified): " + account);
        try {
            Map<String, Object> usage = client.usage(resolved.token);
            if (!account.equals(Json.str(usage, "accountId")))
                throw new IOException("usage account differs from identity; do not rely on these limits");
            getLog().info("Account active: " + (usage.get("active") instanceof Boolean ? usage.get("active") : "not reported"));
            for (String line : UsageSummary.lines(usage)) getLog().info(line);
        } catch (IOException e) {
            getLog().warn("Identity verified, but limits unavailable: " + e.getMessage());
            throw new MojoExecutionException("Compiler account identified; effective limits could not be verified.");
        }
        getLog().info("For a refused build, share its accountId and request ID from the failure message with support."
                + " This is a current account snapshot, not a generation attempt. The Analyser has a separate sign-in.");
    }
}
