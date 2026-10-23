package com.fluxtion.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which compiler credential a generation goal uses, and how it reaches the in-process Fluxtion builder.
 *
 * <p>Precedence: the {@value #ENV_TOKEN} environment variable (CI), then the credential stored by
 * {@code fluxtion:login}, then none. With a credential the builder runs in token mode
 * ({@code fluxtion.remote.auth=token}, {@code fluxtion.remote.token}); with none, nothing is set and the builder's
 * existing RapidAPI-key behaviour is untouched. A credential is never accepted from a POM.</p>
 */
final class CompilerCredentials {

    static final String ENV_TOKEN = "FLUXTION_API_TOKEN";
    static final String ENV_URL = "FLUXTION_COMPILER_URL";
    static final String ENV_CI = "CI";

    static final String PROP_AUTH = "fluxtion.remote.auth";
    static final String PROP_TOKEN = "fluxtion.remote.token";
    static final String PROP_HOST = "fluxtion.remote.host";
    static final String PROP_PORT = "fluxtion.remote.port";

    /** POM property names that would carry a credential; refused. */
    static final List<String> FORBIDDEN_POM_PROPERTIES = Collections.unmodifiableList(Arrays.asList(
            PROP_TOKEN, ENV_TOKEN, "fluxtion.token", "fluxtion.apiToken", "fluxtion.api.token"));

    static final String USE_ENV_OR_LOGIN = "Compiler credentials are never read from a POM. Use the "
            + ENV_TOKEN + " environment variable (CI) or sign in once with mvn fluxtion:login (developer machine).";

    enum Source { ENVIRONMENT, STORED, NONE }

    static final class Resolved {
        final Source source;
        final String token;
        final CompilerUrl url;

        Resolved(Source source, String token, CompilerUrl url) {
            this.source = source;
            this.token = token;
            this.url = url;
        }

        @Override
        public String toString() {
            return "Resolved{source=" + source + ", url=" + url + "}";
        }
    }

    private CompilerCredentials() {
    }

    /** CI systems that do not set {@code CI} (Jenkins, Azure Pipelines, TeamCity, CodeBuild) still set one of these. */
    static final List<String> CI_MARKERS = Collections.unmodifiableList(Arrays.asList(
            "JENKINS_URL", "TF_BUILD", "TEAMCITY_VERSION", "CODEBUILD_BUILD_ID", "BUILDKITE", "GITHUB_ACTIONS",
            "GITLAB_CI", "BITBUCKET_BUILD_NUMBER"));

    /**
     * The system properties carry one credential for the whole JVM, so two generation goals running in parallel
     * ({@code mvn -T}) must not overlap: one would restore (clear) the other's credential mid-generation, sending
     * that generation down the RapidAPI path, and the last to restore would put a credential back for good.
     */
    private static final ReentrantLock BUILDER_PROPERTIES = new ReentrantLock();

    static boolean isCi(Function<String, String> env) {
        String ci = env.apply(ENV_CI);
        if (ci != null && !ci.trim().isEmpty() && !"false".equalsIgnoreCase(ci.trim())) return true;
        for (String marker : CI_MARKERS) {
            String v = env.apply(marker);
            if (v != null && !v.trim().isEmpty()) return true;
        }
        return false;
    }

    /** The configured compiler base URL: the goal's {@code compilerUrl} parameter, else {@value #ENV_URL}, else null. */
    static String configuredUrl(String parameter, Function<String, String> env) {
        if (parameter != null && !parameter.trim().isEmpty()) return parameter.trim();
        String e = env.apply(ENV_URL);
        return e == null || e.trim().isEmpty() ? null : e.trim();
    }

    /**
     * Fails the build when a POM tries to carry a credential: through a credential-shaped goal parameter, or a
     * credential-shaped property in the POM itself or one of its profiles.
     */
    static void refusePomCredential(MavenProject project, String... parameterValues) throws MojoExecutionException {
        for (String v : parameterValues) {
            if (v != null && !v.trim().isEmpty()) {
                throw new MojoExecutionException("A compiler credential was set in the plugin <configuration>. "
                        + USE_ENV_OR_LOGIN + " Remove it from the POM, and revoke it if the POM was committed.");
            }
        }
        if (project == null) return;
        // this POM and every parent POM: a credential inherited from a parent is just as committed
        Set<MavenProject> seen = Collections.newSetFromMap(new IdentityHashMap<MavenProject, Boolean>());
        for (MavenProject p = project; p != null && seen.add(p); p = p.getParent()) {
            Model model = p.getOriginalModel();
            if (model == null) continue;
            String pom = p == project ? "the POM" : "the parent POM " + model.getArtifactId();
            checkProperties(model.getProperties(), pom + "'s <properties>");
            if (model.getProfiles() != null) {
                for (Profile profile : model.getProfiles()) {
                    checkProperties(profile.getProperties(), pom + "'s profile '" + profile.getId() + "'");
                }
            }
        }
        refuseMvnConfig(project.getBasedir());
    }

    /**
     * {@code .mvn/maven.config} and {@code .mvn/jvm.config} are committed with the project and turn {@code -D}
     * options into system properties the builder reads directly, so a credential there bypasses the POM check.
     */
    private static void refuseMvnConfig(File basedir) throws MojoExecutionException {
        if (basedir == null) return;
        for (Path dir = basedir.toPath().toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path mvn = dir.resolve(".mvn");
            if (!Files.isDirectory(mvn)) continue;
            for (String name : new String[]{"maven.config", "jvm.config"}) {
                Path config = mvn.resolve(name);
                if (!Files.isRegularFile(config)) continue;
                String text;
                try {
                    text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                for (String forbidden : FORBIDDEN_POM_PROPERTIES) {
                    if (Pattern.compile("-D\\s*" + Pattern.quote(forbidden) + "\\s*=").matcher(text).find()) {
                        throw new MojoExecutionException("A compiler credential property (" + forbidden + ") is set in "
                                + config + ", which is committed with the project. " + USE_ENV_OR_LOGIN
                                + " Remove it, and revoke it if it was committed.");
                    }
                }
            }
            return; // Maven uses the nearest .mvn directory only
        }
    }

    private static void checkProperties(Properties props, String where) throws MojoExecutionException {
        if (props == null) return;
        for (String name : FORBIDDEN_POM_PROPERTIES) {
            if (props.containsKey(name)) {
                throw new MojoExecutionException("A compiler credential property (" + name + ") is set in " + where
                        + ". " + USE_ENV_OR_LOGIN + " Remove it from the POM, and revoke it if the POM was committed.");
            }
        }
    }

    /** Applies the precedence: environment, then the stored file, then none. */
    static Resolved resolve(Function<String, String> env, CredentialStore store, String configuredUrl, Log log)
            throws MojoExecutionException {
        CompilerUrl url = parseUrl(configuredUrl);
        String envToken = env.apply(ENV_TOKEN);
        if (envToken != null && !envToken.trim().isEmpty()) {
            if (url == null) {
                // without a URL the builder would send the bearer credential to its default host, the RapidAPI gateway
                throw new MojoExecutionException(ENV_TOKEN + " is set but no compiler URL is configured, so there is"
                        + " nowhere safe to send it. Set " + ENV_URL + " or the plugin's <compilerUrl>.");
            }
            return new Resolved(Source.ENVIRONMENT, envToken.trim(), url);
        }
        CredentialStore.Stored stored;
        try {
            stored = store.read();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not read the stored compiler credential at " + store.file()
                    + " (" + e.getMessage() + "). Run mvn fluxtion:logout and sign in again.", e);
        }
        if (stored != null) {
            if (store.tooOpen()) {
                log.warn("The compiler credential file " + store.file() + " is readable by other users;"
                        + " restrict it with: chmod 600 " + store.file());
            }
            // a stored credential goes only to the service that issued it
            CompilerUrl issuer = stored.url == null ? null : parseUrl(stored.url);
            if (issuer == null) {
                throw new MojoExecutionException("The stored compiler credential at " + store.file() + " does not"
                        + " record which compiler issued it. Run mvn fluxtion:logout and sign in again.");
            }
            if (url != null && !url.sameOrigin(issuer)) {
                throw new MojoExecutionException("The stored compiler credential was issued by " + issuer
                        + ", but the compiler URL is " + url + "; it is never sent to another service. Sign in to "
                        + url + " with mvn fluxtion:login -Dfluxtion.compilerUrl=" + url + ", or remove the"
                        + " compiler URL setting to use " + issuer + ".");
            }
            return new Resolved(Source.STORED, stored.token, issuer);
        }
        return new Resolved(Source.NONE, null, url);
    }

    private static CompilerUrl parseUrl(String text) throws MojoExecutionException {
        if (text == null) return null;
        try {
            return CompilerUrl.parse(text);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    /**
     * Sets the builder's system properties for this credential and returns the earlier values, for
     * {@link #restore(Map)} once the builder returns. With no credential nothing is set (default mode).
     */
    static Map<String, String> apply(Resolved r, Log log) {
        Map<String, String> previous = new LinkedHashMap<>();
        if (r.source == Source.NONE) {
            return previous;
        }
        BUILDER_PROPERTIES.lock(); // released by restore()
        set(previous, PROP_AUTH, "token");
        set(previous, PROP_TOKEN, r.token);
        if (r.url != null) {
            set(previous, PROP_HOST, r.url.builderHost());
            set(previous, PROP_PORT, String.valueOf(r.url.builderPort()));
        }
        String from = r.source == Source.ENVIRONMENT ? ENV_TOKEN : "the credential stored by mvn fluxtion:login";
        log.info("Fluxtion compiler: signed-in mode, using " + from
                + (r.url != null ? " against " + r.url : "; no compiler URL configured, using the builder's own"));
        return previous;
    }

    /** Puts back the values {@link #apply} replaced, so no credential outlives the builder call. */
    static void restore(Map<String, String> previous) {
        if (previous.isEmpty()) return;
        try {
            for (Map.Entry<String, String> e : previous.entrySet()) {
                if (e.getValue() == null) System.clearProperty(e.getKey());
                else System.setProperty(e.getKey(), e.getValue());
            }
        } finally {
            if (BUILDER_PROPERTIES.isHeldByCurrentThread()) BUILDER_PROPERTIES.unlock();
        }
    }

    private static void set(Map<String, String> previous, String key, String value) {
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
