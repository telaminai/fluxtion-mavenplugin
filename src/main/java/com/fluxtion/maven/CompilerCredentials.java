package com.fluxtion.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

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

    static boolean isCi(Function<String, String> env) {
        String ci = env.apply(ENV_CI);
        return ci != null && !ci.trim().isEmpty() && !"false".equalsIgnoreCase(ci.trim());
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
        Model model = project == null ? null : project.getOriginalModel();
        if (model == null) return;
        checkProperties(model.getProperties(), "the POM's <properties>");
        if (model.getProfiles() != null) {
            for (Profile profile : model.getProfiles()) {
                checkProperties(profile.getProperties(), "the POM profile '" + profile.getId() + "'");
            }
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
            if (url == null && stored.url != null) {
                url = parseUrl(stored.url);
            }
            return new Resolved(Source.STORED, stored.token, url);
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
        for (Map.Entry<String, String> e : previous.entrySet()) {
            if (e.getValue() == null) System.clearProperty(e.getKey());
            else System.setProperty(e.getKey(), e.getValue());
        }
    }

    private static void set(Map<String, String> previous, String key, String value) {
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
