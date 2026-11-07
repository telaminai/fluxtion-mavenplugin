package com.fluxtion.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs before the first compile of an ensure-mode build, and makes the committed processor safe to compile:
 * <ol>
 *   <li>completes a promotion into {@code src/} that an earlier build was killed in the middle of
 *   ({@link PromotionJournal}), so nothing compiles or digests a half-promoted package;</li>
 *   <li>checks whether the committed processor still compiles against the current sources and dependencies. When it
 *   does not (a node or a dependency it references was removed or changed), it sets {@code excludeProperty} to the
 *   generated package, which the scaffold's {@code default-compile} excludes, so the stale processor cannot stop the
 *   build that regenerates it. Before this, only {@code -Pgenerate-fluxtion} got past that compile.</li>
 * </ol>
 * The check compiles the committed processor alone ({@code -implicit:none}, no annotation processing) against the
 * project's sources and its compile classpath WITHOUT {@code target/classes}, whose stale classes from an earlier build
 * would otherwise hide a removed class. The property is always set: to the generated package when stale, otherwise to
 * a pattern that matches nothing.
 */
@Mojo(name = "prepare", requiresProject = true, defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class FluxtionPrepareMojo extends AbstractMojo {

    /** The value the exclusion takes when the committed processor compiles: an exclude matching nothing. */
    static final String EXCLUDES_NOTHING = "fluxtion-processor-current/excludes-nothing";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project;

    @Parameter(property = "fluxtion.generatedPackage", required = true)
    protected String generatedPackage;

    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    protected String sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources")
    protected String resourcesDirectory;

    /** The project property the scaffold's {@code default-compile} {@code <exclude>} reads. */
    @Parameter(defaultValue = "fluxtion.generated.exclude")
    protected String excludeProperty;

    /** Whether to check that the committed processor compiles; when false the exclusion is never set. */
    @Parameter(property = "fluxtion.staleCheck", defaultValue = "true")
    protected boolean staleCheck;

    @Override
    public void execute() throws MojoExecutionException {
        if (System.getProperty("skipFluxtion") != null) {
            getLog().info("Fluxtion prepare skipped.");
            return;
        }
        String pkg = generatedPackage == null ? "" : generatedPackage.trim();
        if (pkg.isEmpty()) {
            throw new MojoExecutionException("prepare needs <generatedPackage>, the package the scan generates into");
        }
        String packagePath = pkg.replace('.', '/');
        Path javaPackage = Paths.get(sourceDirectory).resolve(packagePath);
        Path resourcePackage = Paths.get(resourcesDirectory).resolve(packagePath);
        PromotionJournal.recover(resourcePackage, Arrays.asList(javaPackage, resourcePackage),
                Paths.get(project.getBuild().getDirectory(), PromotionJournal.LOCK), getLog());
        String exclusion = EXCLUDES_NOTHING;
        if (staleCheck) {
            String problem = staleProblem(javaPackage);
            if (problem != null) {
                exclusion = packagePath + "/**";
                getLog().warn("Fluxtion prepare: the committed processor in " + pkg + " does not compile against the"
                        + " current sources (" + problem + "). It is left out of the first compile; the ensure scan then"
                        + " decides as usual (a changed request regenerates it; an unchanged one is reused and compiled"
                        + " from staging, where the same error would stop the build — then run -Pgenerate-fluxtion).");
            }
        }
        String current = project.getProperties().getProperty(excludeProperty);
        if (current == null || EXCLUDES_NOTHING.equals(current)) {
            project.getProperties().setProperty(excludeProperty, exclusion);
        }
        getLog().debug("Fluxtion prepare: " + excludeProperty + "=" + project.getProperties().getProperty(excludeProperty));
    }

    /** Whether any classpath element declares an annotation processor service (what javac discovers by default). */
    static boolean hasClasspathProcessors(List<String> classpath) {
        String service = "META-INF/services/javax.annotation.processing.Processor";
        for (String element : classpath) {
            File file = new File(element);
            if (file.isDirectory()) {
                if (new File(file, service).isFile()) return true;
            } else if (file.isFile() && file.getName().endsWith(".jar")) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(file)) {
                    if (jar.getEntry(service) != null) return true;
                } catch (IOException unreadable) {
                    // not a jar javac could read either
                }
            }
        }
        return false;
    }

    private List<File> allSources(List<File> processors) throws MojoExecutionException {
        java.util.Set<File> all = new java.util.LinkedHashSet<>(processors);
        for (String root : project.getCompileSourceRoots()) {
            Path r = Paths.get(root);
            if (!Files.isDirectory(r)) continue;
            try (Stream<Path> walk = Files.walk(r)) {
                walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).map(Path::toFile).forEach(all::add);
            } catch (IOException e) {
                throw new MojoExecutionException("Fluxtion prepare: cannot list " + r, e);
            }
        }
        return new ArrayList<>(all);
    }

    /** What maven-compiler-plugin's default-compile is configured with, as far as the check needs it. */
    static final class CompilerSettings {
        String release;
        String source;
        String target;
        boolean processorPathConfigured;

        static CompilerSettings of(MavenProject project) {
            CompilerSettings s = new CompilerSettings();
            java.util.Properties props = project.getProperties();
            s.release = blank(props.getProperty("maven.compiler.release"));
            s.source = blank(props.getProperty("maven.compiler.source"));
            s.target = blank(props.getProperty("maven.compiler.target"));
            for (org.apache.maven.model.Plugin plugin : project.getBuildPlugins()) {
                if (!"maven-compiler-plugin".equals(plugin.getArtifactId())) continue;
                s.apply(plugin.getConfiguration(), props);
                for (org.apache.maven.model.PluginExecution execution : plugin.getExecutions()) {
                    if ("default-compile".equals(execution.getId())) s.apply(execution.getConfiguration(), props);
                }
            }
            return s;
        }

        private void apply(Object conf, java.util.Properties props) {
            if (!(conf instanceof org.codehaus.plexus.util.xml.Xpp3Dom)) return;
            org.codehaus.plexus.util.xml.Xpp3Dom dom = (org.codehaus.plexus.util.xml.Xpp3Dom) conf;
            String r = value(dom, "release", props);
            if (r != null) release = r;
            String so = value(dom, "source", props);
            if (so != null) source = so;
            String t = value(dom, "target", props);
            if (t != null) target = t;
            if (dom.getChild("annotationProcessorPaths") != null || dom.getChild("annotationProcessors") != null
                    ) {
                processorPathConfigured = true;
            }
            org.codehaus.plexus.util.xml.Xpp3Dom args = dom.getChild("compilerArgs");
            if (args != null) {
                for (org.codehaus.plexus.util.xml.Xpp3Dom arg : args.getChildren()) {
                    String v = arg.getValue();
                    if (v != null && (v.trim().equals("-processor") || v.trim().equals("-processorpath")
                            || v.trim().equals("--processor-path"))) processorPathConfigured = true;
                }
            }
        }

        private static String value(org.codehaus.plexus.util.xml.Xpp3Dom dom, String name, java.util.Properties props) {
            org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild(name);
            if (child == null) return null;
            String v = blank(child.getValue());
            if (v == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\$\\{([^}]+)}$").matcher(v);
            return m.matches() ? blank(props.getProperty(m.group(1))) : v;
        }

        private static String blank(String v) {
            return v == null || v.trim().isEmpty() ? null : v.trim();
        }
    }

    /** The first compile error of the committed processor, or null when it compiles (or cannot be checked). */
    String staleProblem(Path javaPackage) throws MojoExecutionException {
        List<File> processors;
        if (!Files.isDirectory(javaPackage, LinkOption.NOFOLLOW_LINKS)) return null;
        try (Stream<Path> list = Files.list(javaPackage)) {
            processors = list.filter(p -> p.getFileName().toString().endsWith(".java")
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).map(Path::toFile).collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion prepare: cannot list " + javaPackage, e);
        }
        if (processors.isEmpty()) return null;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            getLog().info("Fluxtion prepare: no Java compiler in this JVM, so the committed processor is not checked.");
            return null;
        }
        List<String> classpath;
        try {
            classpath = new ArrayList<>(project.getCompileClasspathElements());
        } catch (Exception e) {
            throw new MojoExecutionException("Fluxtion prepare: cannot resolve the compile classpath", e);
        }
        String output = new File(project.getBuild().getOutputDirectory()).getAbsolutePath();
        classpath.removeIf(element -> new File(element).getAbsolutePath().equals(output));
        Path probe = Paths.get(project.getBuild().getDirectory(), "fluxtion-prepare", "classes");
        try {
            Files.createDirectories(probe);
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion prepare: cannot create " + probe, e);
        }
        CompilerSettings settings = CompilerSettings.of(project);
        if (settings.processorPathConfigured) {
            getLog().info("Fluxtion prepare: maven-compiler-plugin configures annotation processors"
                    + " (annotationProcessorPaths/annotationProcessors/-processor), which this check cannot reproduce;"
                    + " the committed processor is not checked. If it no longer compiles, build with -Pgenerate-fluxtion.");
            return null;
        }
        boolean classpathProcessors = hasClasspathProcessors(classpath);
        List<String> options = new ArrayList<>(Arrays.asList("-nowarn", "-Xlint:none", "-d", probe.toString(),
                "-classpath", String.join(File.pathSeparator, classpath)));
        List<File> units = new ArrayList<>(processors);
        if (classpathProcessors) {
            // processors found on the classpath (e.g. Lombok) run as default-compile runs them: over every source,
            // explicitly, because javac does not process sources it compiles implicitly
            options.addAll(Arrays.asList("-processorpath", String.join(File.pathSeparator, classpath), "-implicit:none"));
            units = allSources(processors);
        } else {
            options.addAll(Arrays.asList("-proc:none", "-implicit:none",
                    "-sourcepath", String.join(File.pathSeparator, project.getCompileSourceRoots())));
        }
        if (settings.release != null) {
            options.add("--release");
            options.add(settings.release);
        } else {
            if (settings.source != null) { options.add("-source"); options.add(settings.source); }
            if (settings.target != null) { options.add("-target"); options.add(settings.target); }
        }
        String encoding = project.getProperties().getProperty("project.build.sourceEncoding");
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        long start = System.nanoTime();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, charset)) {
            Boolean ok = javac.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjectsFromFiles(units)).call();
            getLog().debug("Fluxtion prepare: committed processor check took "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
            if (Boolean.TRUE.equals(ok)) return null;
        } catch (IOException | RuntimeException e) {
            getLog().info("Fluxtion prepare: the committed processor could not be checked (" + e + ")");
            return null;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                String where = d.getSource() == null ? "" : new File(d.getSource().toUri()).getName() + ":" + d.getLineNumber() + ": ";
                return where + d.getMessage(Locale.ROOT).split("\n")[0];
            }
        }
        return "it does not compile";
    }
}
