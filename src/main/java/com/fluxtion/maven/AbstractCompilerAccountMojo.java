package com.fluxtion.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.function.Function;

/** Shared configuration of the {@code login} and {@code logout} goals. */
abstract class AbstractCompilerAccountMojo extends AbstractMojo {

    /**
     * The compiler service's base URL, e.g. {@code https://compiler.example.com}; also read from the
     * {@code FLUXTION_COMPILER_URL} environment variable.
     */
    @Parameter(property = "fluxtion.compilerUrl")
    protected String compilerUrl;

    /** Environment lookup; replaced in tests. */
    Function<String, String> env = System::getenv;

    /** Where the credential is stored; null means the default location ({@code ~/.fluxtion/credentials}). */
    CredentialStore credentialStore;

    /** Builds the service client; replaced in tests. */
    Function<CompilerUrl, CompilerAuthClient> clientFactory = CompilerAuthClient::new;

    CredentialStore credentialStore() {
        return credentialStore != null ? credentialStore : CredentialStore.defaultStore();
    }

    /** The configured URL, else {@code fallback} (the stored one), else null. */
    CompilerUrl compilerUrl(String fallback) throws MojoExecutionException {
        String text = CompilerCredentials.configuredUrl(compilerUrl, env);
        if (text == null) text = fallback;
        if (text == null) return null;
        try {
            return CompilerUrl.parse(text);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }
}
