package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;

/**
 * Signs this machine out: revokes the stored credential on the compiler service (best effort) and deletes
 * {@code ~/.fluxtion/credentials}. Does not touch a {@code FLUXTION_API_TOKEN} set in the environment.
 */
@Mojo(name = "logout", requiresProject = false, aggregator = true, threadSafe = true)
public class FluxtionLogoutMojo extends AbstractCompilerAccountMojo {

    @Override
    public void execute() throws MojoExecutionException {
        CredentialStore store = credentialStore();
        CredentialStore.Stored stored;
        try { stored = store.read(); }
        catch (IOException e) { throw new MojoExecutionException("Stored sign-in could not be read; nothing deleted or revoked."); }
        if (stored == null) { getLog().info("Not signed in: no stored compiler credential."); envNote(); return; }
        if (stored != null) {
            revoke(stored);
        }
        try {
            if (!store.compareAndDelete(stored)) {
                getLog().warn("Another sign-in replaced this one; the new sign-in was retained.");
                envNote(); return;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Revocation was attempted, but the local sign-in could not be removed from " + store.file()
                    + ". Check this file and its " + store.file().getFileName() + ".lock sibling for symbolic links or invalid permissions. "
                    + "Do not follow or delete a link target. Correct the local path and rerun logout; "
                    + "the retained credential may already be revoked.");
        }
        getLog().info("Signed out: removed " + store.file() + ".");
        envNote();
    }

    private void revoke(CredentialStore.Stored stored) {
        // only the service that issued the credential ever sees it: a configured compilerUrl is not consulted
        CompilerUrl url;
        try {
            url = stored.url == null ? null : CompilerUrl.parse(stored.url);
        } catch (IllegalArgumentException e) {
            url = null;
        }
        if (url == null || stored.credentialId == null) {
            getLog().warn("Could not revoke the credential on the service (no compiler URL or credential id"
                    + " recorded); revoke it from the account's credentials list.");
            return;
        }
        try {
            int status = clientFactory.apply(url).revoke(stored.credentialId, stored.token);
            if (status == 204 || status == 200) {
                getLog().info("Revoked credential " + stored.credentialId + " on " + url + ".");
            } else if (status == 404) {
                getLog().info("Credential " + stored.credentialId + " was already revoked or expired.");
            } else if (status == 401) {
                // a 401 cannot tell a revoked credential from one the request never delivered (an address in front
                // of the service that drops Authorization): never report it as revoked
                getLog().warn("The service did not accept credential " + stored.credentialId + " (HTTP 401). It may"
                        + " already be revoked; if not, revoke it from the account's credentials list.");
            } else {
                getLog().warn("Could not revoke credential " + stored.credentialId + " (HTTP " + status
                        + "); revoke it from the account's credentials list.");
            }
        } catch (IOException e) {
            getLog().warn("Could not revoke credential " + stored.credentialId + " (" + e.getMessage()
                    + "); revoke it from the account's credentials list.");
        }
    }

    private void envNote() {
        String t = env.apply(CompilerCredentials.ENV_TOKEN);
        if (t != null && !t.trim().isEmpty()) {
            getLog().info(CompilerCredentials.ENV_TOKEN + " is set in this environment and still takes precedence;"
                    + " logout does not change it.");
        }
    }
}
