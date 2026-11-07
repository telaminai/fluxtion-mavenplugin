package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author greg higgins
 */
@Mojo(name = "scan",
        requiresProject = true,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        defaultPhase = LifecyclePhase.COMPILE
)
public class FluxtionScanToGenMojo extends AbstractFluxtionMojo {

    @Parameter(property = "buildDirectory", defaultValue = "target/classes")
    protected String buildDirectory;

    @Parameter(property = "outputDirectory")
    protected String outputDirectory;

    @Parameter(property = "resourcesDirectory")
    protected String resourcesDirectory;

    /**
     * Ensure mode: reuse the committed processor when the compile request is unchanged, otherwise regenerate
     * (signed in) or fail with FLX-1022. Needs a builder that reads the {@code FLUXTION.ENSURE.*} properties; an
     * older builder ignores them and generates as before.
     */
    @Parameter(property = "fluxtion.ensureProcessor", defaultValue = "false")
    protected boolean ensureProcessor;

    /** Where the committed processor source lives; ensure mode only. */
    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    protected String ensureSourceDirectory;

    /** Where the committed request stamp lives; ensure mode only. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources")
    protected String ensureResourcesDirectory;

    /** Optional package prefix the request digest is made independent of; ensure mode only. */
    @Parameter
    protected String relocatableBase;

    /**
     * Forces generation in ensure mode. Read as a parameter so a profile's {@code <fluxtion.regenerate>} property
     * reaches the builder as well as {@code -Dfluxtion.regenerate=true}.
     */
    @Parameter(property = REGENERATE)
    protected String regenerate;
    public static final String GENERATOR_METHOD = "scanAndGenerateFluxtionBuilders";
    public static final String FLUXTION_GENERATOR_CLASS = "com.telamin.fluxtion.Fluxtion";
    public static final String OUTPUT_DIRECTORY = "FLUXTION.OUTPUT.DIRECTORY";
    public static final String RESOURCES_DIRECTORY = "FLUXTION.RESOURCES.DIRECTORY";
    public static final String ENSURE_SOURCE_DIRECTORY = "FLUXTION.ENSURE.SOURCE.DIRECTORY";
    public static final String ENSURE_RESOURCES_DIRECTORY = "FLUXTION.ENSURE.RESOURCES.DIRECTORY";
    public static final String ENSURE_RELOCATABLE_BASE = "FLUXTION.ENSURE.RELOCATABLE.BASE";
    public static final String REGENERATE = "fluxtion.regenerate";
    /** A class only a builder with ensure support (fluxtion-builder 1.0.87 or later) has; probed, never initialised. */
    public static final String ENSURE_SUPPORT_CLASS = "com.telamin.fluxtion.builder.compile.generation.EnsureProcessor";

    /** The class probed for ensure support; replaced in tests. */
    String ensureSupportClass = ENSURE_SUPPORT_CLASS;

    /**
     * A class only fluxtion-builder 1.0.90 or later has: the builder that reads a compiler sign-in itself and refuses a
     * signed-out generation with FLX-1023. Probed, never initialised.
     */
    public static final String SIGN_IN_SUPPORT_CLASS =
            "com.telamin.fluxtion.builder.compile.generation.remote.client.CompilerSignIn";

    /** The class probed for 1.0.90 sign-in support; replaced in tests. */
    String signInSupportClass = SIGN_IN_SUPPORT_CLASS;

    @Override
    public void execute() throws MojoExecutionException {
        if (System.getProperty("skipFluxtion") != null) {
            getLog().info("Fluxtion generation skipped.");
        } else {
            CompilerCredentials.lockBuilderProperties(); // one builder call at a time under -T
            try {
                generate();
            } finally {
                CompilerCredentials.unlockBuilderProperties();
            }
        }
    }

    private void generate() throws MojoExecutionException {
        Map<String, String> previousAuth = applyCompilerAuth();
        Map<String, String> previousProperties = new LinkedHashMap<>();
        try {
            if (buildDirectory == null) {
                buildDirectory = project.getBasedir().getCanonicalPath() + "/target/classes";
            } else if (!buildDirectory.startsWith("/")) {
                buildDirectory = project.getBasedir().getCanonicalPath() + "/" + buildDirectory;
            }
            if (outputDirectory == null) {
                outputDirectory = project.getBuild().getSourceDirectory();
            }
            if (resourcesDirectory == null) {
                resourcesDirectory = project.getBasedir().getCanonicalPath() + "/src/main/resources";
            }
            setProperty(previousProperties, OUTPUT_DIRECTORY, outputDirectory);
            setProperty(previousProperties, RESOURCES_DIRECTORY, resourcesDirectory);
            if (ensureProcessor) {
                applyEnsureProperties(previousProperties);
            }
            URLClassLoader classLoader = buildFluxtionClassLoader();
            if (ensureProcessor) {
                requireEnsureSupport(classLoader);
            }
            warnOnABuilderBefore1090(classLoader);
            ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(classLoader);
                Class<?> genClass = classLoader.loadClass(FLUXTION_GENERATOR_CLASS);
                Method generatorMethod = genClass.getMethod(GENERATOR_METHOD, ClassLoader.class, File[].class);
                generatorMethod.invoke(null, classLoader, new File[]{new File(buildDirectory)});
            } finally {
                Thread.currentThread().setContextClassLoader(oldContextClassLoader);
            }
        } catch (MojoExecutionException refused) {
            throw refused;
        } catch (Exception exception) {
            logGenerationFailure(exception);
            throw generationFailure("problem setting building fluxtion class loader", exception);
        } finally {
            restoreProperties(previousProperties);
            CompilerCredentials.restore(previousAuth);
        }
    }

    /** Sets the builder's ensure-mode properties; the ensure source directory being set is what enables it. */
    private void applyEnsureProperties(Map<String, String> previous) throws java.io.IOException {
        String base = project.getBasedir().getCanonicalPath();
        String sourceDirectory = ensureSourceDirectory != null ? ensureSourceDirectory
                : project.getBuild().getSourceDirectory();
        String resources = ensureResourcesDirectory != null ? ensureResourcesDirectory
                : base + "/src/main/resources";
        setProperty(previous, ENSURE_SOURCE_DIRECTORY, absolute(base, sourceDirectory));
        setProperty(previous, ENSURE_RESOURCES_DIRECTORY, absolute(base, resources));
        if (relocatableBase != null && !relocatableBase.trim().isEmpty()) {
            setProperty(previous, ENSURE_RELOCATABLE_BASE, relocatableBase.trim());
        }
        if (regenerate != null && !regenerate.trim().isEmpty()) {
            setProperty(previous, REGENERATE, regenerate.trim());
        }
        getLog().info("Fluxtion ensure mode: committed processor in " + System.getProperty(ENSURE_SOURCE_DIRECTORY)
                + (Boolean.parseBoolean(System.getProperty(REGENERATE)) ? ", regeneration forced" : ""));
    }

    /**
     * Fails before generation when the project's builder predates ensure mode: an older builder ignores the ensure
     * properties and would regenerate (or need a key) on every build, silently not doing what the POM asks.
     */
    private void requireEnsureSupport(ClassLoader classLoader) throws MojoExecutionException {
        try {
            Class.forName(ensureSupportClass, false, classLoader);
        } catch (ClassNotFoundException | LinkageError oldBuilder) {
            throw new MojoExecutionException("ensureProcessor needs com.telamin.fluxtion:fluxtion-builder 1.0.87 or"
                    + " later on the project's build classpath; this project's builder has no ensure support ("
                    + ensureSupportClass + " not found). Upgrade the builder, or remove ensureProcessor.");
        }
    }

    /**
     * The plugin runs every builder in token mode, so none falls back to the retired key route. A builder before 1.0.90
     * then refuses a signed-out generation with an uncoded message rather than FLX-1023; say which version fixes that.
     */
    private void warnOnABuilderBefore1090(ClassLoader classLoader) {
        try {
            Class.forName(signInSupportClass, false, classLoader);
        } catch (ClassNotFoundException | LinkageError oldBuilder) {
            getLog().warn("This project's com.telamin.fluxtion:fluxtion-builder is older than 1.0.90. Upgrade it (or the"
                    + " fluxtion-bom) to 1.0.90 or later: the RapidAPI key route is retired, and 1.0.90 is the builder"
                    + " that finds your compiler sign-in on its own and explains a missing one (FLX-1023).");
        }
    }

    private static String absolute(String base, String path) {
        return new File(path).isAbsolute() ? path : new File(base, path).getPath();
    }

    private static void setProperty(Map<String, String> previous, String key, String value) {
        if (!previous.containsKey(key)) {
            previous.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    /** Puts back every property this goal set, so nothing outlives the scan (a later goal in the same JVM). */
    private static void restoreProperties(Map<String, String> previous) {
        for (Map.Entry<String, String> e : previous.entrySet()) {
            if (e.getValue() == null) System.clearProperty(e.getKey());
            else System.setProperty(e.getKey(), e.getValue());
        }
    }
}

