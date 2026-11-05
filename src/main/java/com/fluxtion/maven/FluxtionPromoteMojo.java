package com.fluxtion.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Promotes a staged scan (the {@code scan} goal writing to a staging directory, then compiled from there) into the
 * project: the staged resources always go to {@code target/classes}, so the jar carries this build's GraphML; the
 * generated package in {@code src/} is replaced only when the scan generated.
 *
 * <p>When every staged processor carries the builder's {@code <Processor>.fluxtion-reused} marker the scan reused the
 * committed source, and {@code src/} is not touched at all, so an interrupted reuse build cannot damage it. Otherwise
 * the package is replaced file by file: each file is written to a temporary file and renamed, a file whose bytes are
 * unchanged is not written, and a file the scan no longer writes is deleted. PNG files are never promoted to
 * {@code src/}, and the marker is never copied anywhere.
 */
@Mojo(name = "promote", requiresProject = true, defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class FluxtionPromoteMojo extends AbstractMojo {

    /** Written by the builder beside a reused processor in the staged resources; read here, never promoted. */
    public static final String REUSED_MARKER_SUFFIX = ".fluxtion-reused";

    /** The generated package, e.g. {@code com.example.myapp.generated}: the only part of {@code src/} replaced. */
    @Parameter(property = "fluxtion.generatedPackage", required = true)
    protected String generatedPackage;

    @Parameter(defaultValue = "${project.build.directory}/fluxtion-staging/java")
    protected String stagingSourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/fluxtion-staging/resources")
    protected String stagingResourcesDirectory;

    @Parameter(defaultValue = "${project.build.sourceDirectory}")
    protected String sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/main/resources")
    protected String resourcesDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    protected String classesDirectory;

    /** Skips promotion; {@code -DskipFluxtion} also skips it, since the scan did not run. */
    @Parameter(property = "fluxtion.promote.skip", defaultValue = "false")
    protected boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip || System.getProperty("skipFluxtion") != null) {
            getLog().info("Fluxtion promote skipped.");
            return;
        }
        if (generatedPackage == null || generatedPackage.trim().isEmpty()) {
            throw new MojoExecutionException("promote needs <generatedPackage>, the package the scan generates into");
        }
        String packagePath = generatedPackage.trim().replace('.', '/');
        Path stagedSources = Paths.get(stagingSourceDirectory);
        Path stagedResources = Paths.get(stagingResourcesDirectory);
        try {
            Counts classes = new Counts();
            for (Path relative : files(stagedResources)) {
                if (!isMarker(relative)) {
                    promote(stagedResources.resolve(relative), Paths.get(classesDirectory).resolve(relative), classes);
                }
            }
            Path stagedJavaPackage = stagedSources.resolve(packagePath);
            Path stagedResourcePackage = stagedResources.resolve(packagePath);
            List<Path> sources = files(stagedJavaPackage);
            List<Path> resources = files(stagedResourcePackage);
            if (sources.isEmpty() && resources.isEmpty()) {
                getLog().warn("Fluxtion promote: nothing staged in " + generatedPackage + "; src/ left unchanged");
                return;
            }
            Set<String> reused = new TreeSet<>();
            Set<String> processors = new TreeSet<>();
            for (Path p : resources) {
                if (p.getNameCount() == 1 && isMarker(p)) reused.add(baseName(p, REUSED_MARKER_SUFFIX));
            }
            for (Path p : sources) {
                if (p.getNameCount() == 1 && p.toString().endsWith(".java")) processors.add(baseName(p, ".java"));
            }
            if (!reused.isEmpty() && reused.containsAll(processors)) {
                getLog().info("Fluxtion promote: " + generatedPackage + " reused (" + String.join(", ", reused)
                        + "), src/ unchanged; " + classes + " in " + classesDirectory);
                return;
            }
            Counts src = new Counts();
            replacePackage(stagedJavaPackage, sources, Paths.get(sourceDirectory).resolve(packagePath), src);
            replacePackage(stagedResourcePackage, resources, Paths.get(resourcesDirectory).resolve(packagePath), src);
            getLog().info("Fluxtion promote: " + generatedPackage + " generated; src/ " + src + ", "
                    + src.deleted + " deleted; " + classes + " in " + classesDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion promote failed: " + e.getMessage(), e);
        }
    }

    /** Writes the staged package over {@code target}, then deletes what the scan no longer writes. */
    private void replacePackage(Path staged, List<Path> files, Path target, Counts counts) throws IOException {
        Set<Path> promoted = new TreeSet<>();
        for (Path relative : files) {
            if (isMarker(relative) || isPng(relative)) continue;
            promote(staged.resolve(relative), target.resolve(relative), counts);
            promoted.add(relative);
        }
        for (Path relative : files(target)) {
            if (!promoted.contains(relative)) {
                Files.delete(target.resolve(relative));
                counts.deleted++;
            }
        }
    }

    /** Temporary file then rename, so a reader never sees a half-written file; unchanged bytes are not rewritten. */
    static void promote(Path from, Path to, Counts counts) throws IOException {
        byte[] bytes = Files.readAllBytes(from);
        if (Files.isRegularFile(to) && Arrays.equals(bytes, Files.readAllBytes(to))) {
            counts.unchanged++;
            return;
        }
        Path dir = to.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "." + to.getFileName(), ".promote");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        counts.written++;
    }

    /** Regular files under {@code root}, relative to it, sorted; empty when it does not exist. */
    static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) return Collections.emptyList();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(root::relativize).sorted().collect(Collectors.toList());
        }
    }

    private static boolean isMarker(Path p) {
        return p.getFileName().toString().endsWith(REUSED_MARKER_SUFFIX);
    }

    private static boolean isPng(Path p) {
        return p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png");
    }

    private static String baseName(Path p, String suffix) {
        String name = p.getFileName().toString();
        return name.substring(0, name.length() - suffix.length());
    }

    static final class Counts {
        int written;
        int unchanged;
        int deleted;

        @Override
        public String toString() {
            return written + " written, " + unchanged + " unchanged";
        }
    }
}
