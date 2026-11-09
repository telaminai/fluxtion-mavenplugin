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

    @org.apache.maven.plugins.annotations.Parameter(defaultValue = "${project}", readonly = true)
    org.apache.maven.project.MavenProject project;

    @org.apache.maven.plugins.annotations.Parameter(defaultValue = "${session}", readonly = true)
    org.apache.maven.execution.MavenSession session;

    /** Direct goals do not inherit a scan execution's configuration. Refuse ambiguity before resolving credentials. */
    void checkExecutionUrls() throws MojoExecutionException {
        String explicit = session == null ? null : session.getUserProperties().getProperty("fluxtion.compilerUrl");
        if (explicit != null && !explicit.trim().isEmpty()) {
            compilerUrl = explicit.trim();
            return;
        }
        java.util.List<org.apache.maven.project.MavenProject> projects = session == null ? null : session.getProjects();
        if (projects == null || projects.isEmpty()) projects = project == null
                ? java.util.Collections.emptyList() : java.util.Collections.singletonList(project);
        String configured = CompilerCredentials.configuredUrl(compilerUrl, env);
        for (org.apache.maven.project.MavenProject selected : projects) {
            checkUrl(selected.getProperties().getProperty("fluxtion.compilerUrl"), configured);
            for (org.apache.maven.model.Plugin plugin : selected.getBuildPlugins()) {
                if (!"com.telamin.fluxtion".equals(plugin.getGroupId())
                        || !"fluxtion-maven-plugin".equals(plugin.getArtifactId())) continue;
                checkConfiguration(plugin.getConfiguration(), configured);
                for (org.apache.maven.model.PluginExecution execution : plugin.getExecutions())
                    checkConfiguration(execution.getConfiguration(), configured);
            }
        }
    }

    private void checkConfiguration(Object config, String configured) throws MojoExecutionException {
        if (!(config instanceof org.codehaus.plexus.util.xml.Xpp3Dom)) return;
        org.codehaus.plexus.util.xml.Xpp3Dom value =
                ((org.codehaus.plexus.util.xml.Xpp3Dom) config).getChild("compilerUrl");
        if (value != null) checkUrl(value.getValue(), configured);
    }

    private void checkUrl(String value, String configured) throws MojoExecutionException {
        if (value == null || value.trim().isEmpty()) return;
        boolean same=value.trim().equals(configured);
        if (!same && configured!=null) {
            try {same=CompilerUrl.parse(value).sameOrigin(CompilerUrl.parse(configured));}
            catch(IllegalArgumentException ignored) { }
        }
        if (!same)
            throw new MojoExecutionException("The selected reactor configures a different compiler service. "
                    + "Run this goal with -Dfluxtion.compilerUrl=<the build's service URL>. No request sent.");
    }

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
