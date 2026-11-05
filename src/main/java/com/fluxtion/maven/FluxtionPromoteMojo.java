package com.fluxtion.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Promotes a staged scan (the {@code scan} goal writing to a staging directory, then compiled from there) into the
 * project: the staged resources always go to {@code target/classes}, so the jar carries this build's GraphML; the
 * generated package in {@code src/} is updated only when the scan generated.
 *
 * <p>When every staged processor carries the builder's {@code <Processor>.fluxtion-reused} marker the scan reused the
 * committed source, and {@code src/} is not touched at all, so an interrupted reuse build cannot damage it. Otherwise
 * the generator's own files are replaced one by one: each is written to a temporary file and renamed, and a file whose
 * bytes are unchanged is not written.
 *
 * <p><b>Only the generator's files are ever written or deleted.</b> Those are, in the package's own directory and never
 * below it, {@code <Processor>.java} in the sources and {@code <Processor>.java}, {@code .graphml}, {@code .png} and
 * {@code .fluxtion-request.json} in the resources, for a processor named by the staging or by a committed stamp. A
 * processor's file the scan no longer writes (a renamed processor) is deleted; anything else in the package, and every
 * sub-package, is left alone. PNG files are never promoted to {@code src/}, and the marker is never copied anywhere.
 *
 * <p>Staging must be emptied before each scan (the staged build clears it at {@code initialize}); this goal promotes
 * whatever processors it finds there.
 */
@Mojo(name = "promote", requiresProject = true, defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class FluxtionPromoteMojo extends AbstractMojo {

    /** Written by the builder beside a reused processor in the staged resources; read here, never promoted. */
    public static final String REUSED_MARKER_SUFFIX = ".fluxtion-reused";
    /** The committed stamp; a processor named by one is the generator's, so its files may be replaced or deleted. */
    public static final String STAMP_SUFFIX = ".fluxtion-request.json";
    /** The suffixes of a processor's files in the resources package, as the builder writes them. */
    private static final List<String> RESOURCE_SUFFIXES = Arrays.asList(".java", ".graphml", ".png", STAMP_SUFFIX);
    private static final Pattern PACKAGE = Pattern.compile(
            "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*");

    /** The generated package, e.g. {@code com.example.myapp.generated}: the only directory of {@code src/} written. */
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
        String pkg = generatedPackage == null ? "" : generatedPackage.trim();
        if (!PACKAGE.matcher(pkg).matches()) {
            throw new MojoExecutionException("promote needs <generatedPackage>, the Java package the scan generates"
                    + " into (e.g. com.example.myapp.generated); got '" + generatedPackage + "'");
        }
        String packagePath = pkg.replace('.', '/');
        Path stagedResources = Paths.get(stagingResourcesDirectory);
        Path stagedJavaPackage = Paths.get(stagingSourceDirectory).resolve(packagePath);
        Path stagedResourcePackage = stagedResources.resolve(packagePath);
        Path srcJavaPackage = Paths.get(sourceDirectory).resolve(packagePath);
        Path srcResourcePackage = Paths.get(resourcesDirectory).resolve(packagePath);
        try {
            Set<String> staged = new TreeSet<>(namesEndingWith(stagedJavaPackage, ".java"));
            Set<String> reused = namesEndingWith(stagedResourcePackage, REUSED_MARKER_SUFFIX);
            staged.addAll(reused);
            if (staged.isEmpty()) {
                throw new MojoExecutionException("Fluxtion promote: staging holds no processor in " + pkg + " ("
                        + stagedJavaPackage + "); nothing promoted. Check that the scan wrote to the staging"
                        + " directories and that <generatedPackage> names the package it generates into.");
            }
            Counts classes = new Counts();
            for (Path relative : files(stagedResources)) {
                if (!relative.getFileName().toString().endsWith(REUSED_MARKER_SUFFIX)) {
                    promote(stagedResources.resolve(relative), Paths.get(classesDirectory).resolve(relative), classes);
                }
            }
            if (reused.containsAll(staged)) {
                getLog().info("Fluxtion promote: " + pkg + " reused (" + String.join(", ", reused)
                        + "), src/ unchanged; " + classes + " in " + classesDirectory);
                return;
            }
            // a reused processor's committed files are already right; only the generated ones and renamed-away
            // ones (named by a committed stamp, no longer staged) are written or deleted
            Set<String> known = new TreeSet<>(staged);
            known.addAll(namesEndingWith(srcResourcePackage, STAMP_SUFFIX));
            known.removeAll(reused);
            Counts src = new Counts();
            for (String processor : known) {
                replace(stagedJavaPackage, srcJavaPackage, processor + ".java", src);
                for (String suffix : RESOURCE_SUFFIXES) {
                    replace(stagedResourcePackage, srcResourcePackage, processor + suffix, src);
                }
            }
            getLog().info("Fluxtion promote: " + pkg + " generated (" + String.join(", ", staged) + "); src/ " + src
                    + ", " + src.deleted + " deleted; " + classes + " in " + classesDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion promote failed: " + e.getMessage(), e);
        }
    }

    /**
     * One generator file: promoted when staged (PNG never), deleted when not staged. A symbolic link or anything
     * other than a regular file is never written over or deleted.
     */
    private static void replace(Path stagedDir, Path srcDir, String name, Counts counts) throws IOException {
        Path from = stagedDir.resolve(name);
        Path to = srcDir.resolve(name);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean png = name.toLowerCase(Locale.ROOT).endsWith(".png");
        if (!png && Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) {
            promote(from, to, counts);
        } else if (Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)
                && (png || !Files.exists(from, LinkOption.NOFOLLOW_LINKS))) {
            Files.delete(to);
            counts.deleted++;
        }
    }

    /** Processor names from the regular files directly in {@code dir} (never below it) ending with {@code suffix}. */
    static Set<String> namesEndingWith(Path dir, String suffix) throws IOException {
        Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return names;
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(suffix) && name.length() > suffix.length()) {
                    names.add(name.substring(0, name.length() - suffix.length()));
                }
            });
        }
        return names;
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
        // not Files.createTempFile: that file is owner-only (0600), and the rename would carry it into src/
        Path temp = dir.resolve("." + to.getFileName() + "." + UUID.randomUUID() + ".promote");
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) && isPosix(to)) {
                // a replaced file keeps its permissions; a new one has the default (umask) ones
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(to, LinkOption.NOFOLLOW_LINKS));
            }
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

    private static boolean isPosix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Regular files under {@code root}, relative to it, sorted; empty when it does not exist. */
    static List<Path> files(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .map(root::relativize).sorted().collect(Collectors.toList());
        }
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
