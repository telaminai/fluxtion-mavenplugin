package com.fluxtion.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What {@code promote} with {@code afterTests} needs to know before it writes {@code src/}: were the tests skipped, are
 * failures ignored, and did the tests of THIS build actually run and pass.
 *
 * <p>Within one phase Maven runs executions in POM declaration order, so a {@code promote} execution bound to
 * {@code test} runs after surefire only when surefire is declared (or injected) first. The gate does not trust the
 * order: it requires surefire reports written during this build, so a misordered POM fails loudly instead of promoting
 * an untested processor. Single module only: the tests that gate a module's processor are that module's tests.
 */
final class TestGate {

    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("\\b(failures|errors)=\"(\\d+)\"");

    private final MavenProject project;
    private final MavenSession session;
    private final Path target;
    private final Log log;

    TestGate(MavenProject project, MavenSession session, Path target, Log log) {
        this.project = project;
        this.session = session;
        this.target = target;
        this.log = log;
    }

    /** {@code -DskipTests}, {@code -Dmaven.test.skip}, or surefire's {@code skip}/{@code skipTests} set. */
    boolean testsSkipped() {
        return flag("skipTests") || flag("maven.test.skip") || surefireFlag("skipTests") || surefireFlag("skip");
    }

    /** {@code -Dmaven.test.failure.ignore} or surefire's {@code testFailureIgnore}: a failing test does not stop the build. */
    boolean failureIgnored() {
        return flag("maven.test.failure.ignore") || surefireFlag("testFailureIgnore");
    }

    /**
     * True when {@code src/} may be written: tests skipped (warned: untested), no test classes, or this build's surefire
     * reports show no failures or errors. False (with a WARN) when the reports show failures that were ignored.
     * Refuses when the tests have not run yet in this build: the execution is ordered before surefire.
     */
    boolean testsPassed() throws IOException, MojoExecutionException {
        if (testsSkipped()) {
            log.warn("Fluxtion promote: tests are skipped, so the regenerated processor is promoted into src/ UNTESTED.");
            return true;
        }
        if (!hasTestClasses()) {
            return true;
        }
        List<Path> reports = freshReports();
        if (reports.isEmpty()) {
            throw new MojoExecutionException("Fluxtion promote: the tests have not run yet in this build, so the"
                    + " processor cannot be promoted after them. Within the test phase executions run in POM order:"
                    + " declare maven-surefire-plugin before fluxtion-maven-plugin, or bind the afterTests execution to"
                    + " prepare-package. src/ was not updated.");
        }
        int failed = 0;
        for (Path report : reports) {
            failed += failures(new String(Files.readAllBytes(report), StandardCharsets.UTF_8));
        }
        if (failed > 0) {
            log.warn("Fluxtion promote: " + failed + " test failure(s)/error(s) in this build were ignored"
                    + " (maven.test.failure.ignore / testFailureIgnore). The regenerated processor is NOT promoted into"
                    + " src/; the next build regenerates it again.");
            return false;
        }
        return true;
    }

    static int failures(String report) {
        int total = 0;
        Matcher suite = SUITE.matcher(report);
        while (suite.find()) {
            Matcher attr = ATTR.matcher(suite.group());
            while (attr.find()) total += Integer.parseInt(attr.group(2));
        }
        return total;
    }

    private boolean hasTestClasses() throws IOException {
        Path testClasses = project != null && project.getBuild() != null && project.getBuild().getTestOutputDirectory() != null
                ? Paths.get(project.getBuild().getTestOutputDirectory()) : target.resolve("test-classes");
        if (!Files.isDirectory(testClasses)) return false;
        try (Stream<Path> walk = Files.walk(testClasses)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        }
    }

    private List<Path> freshReports() throws IOException {
        String configured = surefireValue("reportsDirectory");
        Path dir = configured != null ? Paths.get(configured) : target.resolve("surefire-reports");
        if (!dir.isAbsolute() && project != null) dir = project.getBasedir().toPath().resolve(dir);
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        long since = session != null && session.getRequest() != null && session.getRequest().getStartTime() != null
                ? session.getRequest().getStartTime().getTime() : 0L;
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() >= since;
                        } catch (IOException e) {
                            return false;
                        }
                    }).collect(Collectors.toList());
        }
    }

    /** Whether the project has a promote execution configured with {@code afterTests}. */
    static boolean hasAfterTestsExecution(MavenProject project) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!"fluxtion-maven-plugin".equals(plugin.getArtifactId())) continue;
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!execution.getGoals().contains("promote")) continue;
                Object conf = execution.getConfiguration();
                if (conf instanceof Xpp3Dom) {
                    Xpp3Dom after = ((Xpp3Dom) conf).getChild("afterTests");
                    if (after != null && "true".equalsIgnoreCase(String.valueOf(after.getValue()).trim())) return true;
                }
            }
        }
        return false;
    }

    private boolean flag(String key) {
        String value = property(key);
        return value != null && (value.trim().isEmpty() || Boolean.parseBoolean(value.trim()));
    }

    private boolean surefireFlag(String child) {
        String value = surefireValue(child);
        return value != null && Boolean.parseBoolean(value.trim());
    }

    /** A surefire configuration value, plugin-level or from the default-test execution, with ${...} resolved. */
    private String surefireValue(String child) {
        if (project == null) return null;
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!SUREFIRE.equals(plugin.getArtifactId())) continue;
            String value = child(plugin.getConfiguration(), child);
            for (PluginExecution execution : plugin.getExecutions()) {
                if ("default-test".equals(execution.getId())) {
                    String v = child(execution.getConfiguration(), child);
                    if (v != null) value = v;
                }
            }
            return resolve(value);
        }
        return null;
    }

    private static String child(Object conf, String name) {
        if (!(conf instanceof Xpp3Dom)) return null;
        Xpp3Dom c = ((Xpp3Dom) conf).getChild(name);
        return c == null ? null : c.getValue();
    }

    private String resolve(String value) {
        if (value == null) return null;
        Matcher m = Pattern.compile("^\\$\\{([^}]+)}$").matcher(value.trim());
        return m.matches() ? property(m.group(1)) : value;
    }

    private String property(String key) {
        if (session != null) {
            Properties user = session.getUserProperties();
            if (user != null && user.getProperty(key) != null) return user.getProperty(key);
        }
        if (System.getProperty(key) != null) return System.getProperty(key);
        if (project != null && project.getProperties() != null) return project.getProperties().getProperty(key);
        return null;
    }
}
