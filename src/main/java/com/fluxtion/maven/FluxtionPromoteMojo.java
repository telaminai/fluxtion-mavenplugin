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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
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
 * {@code .fluxtion-request.json} in the resources, for a processor named by staging or by a committed stamp. Existing destinations require a valid committed
 * ownership stamp identifying that processor or an unchanged prior promotion marker; a staged name alone cannot
 * authorize replacement. After successful promotion, a separate content-hashed ownership marker also supports
 * custom-template output, which intentionally has no reusable request stamp. A
 * processor's file the scan no longer writes (a renamed processor) is deleted; anything else in the package, and every
 * sub-package, is left alone. PNG files are never promoted to {@code src/}, and the marker is never copied anywhere.
 * A symbolic link below a configured source or classes root, or at a destination file, refuses the build before
 * anything is written. Configured roots may themselves be linked.
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
    /** Ownership is independent of reuse: custom templates intentionally have no reusable request stamp. */
    static final String OWNERSHIP_SUFFIX = ".fluxtion-owned.json";
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
            boolean reuse = reused.containsAll(staged);
            // a reused processor's committed files are already right; only the generated ones and renamed-away
            // ones (named by a committed stamp, no longer staged) are written or deleted
            Set<String> known = new TreeSet<>(staged);
            if (!reuse) {
                // checked before anything is written, so a refusal leaves src/ and target/classes as they were
                requireNoLinks(Paths.get(sourceDirectory), packagePath);
                requireNoLinks(Paths.get(resourcesDirectory), packagePath);
                known.addAll(namesEndingWith(srcResourcePackage, STAMP_SUFFIX));
                known.addAll(namesEndingWith(srcResourcePackage, OWNERSHIP_SUFFIX));
                known.removeAll(reused);
                for (String processor : known) {
                    requireRegularOrAbsent(srcJavaPackage.resolve(processor + ".java"));
                    for (String suffix : RESOURCE_SUFFIXES) {
                        requireRegularOrAbsent(srcResourcePackage.resolve(processor + suffix));
                    }
                    requireRegularOrAbsent(srcResourcePackage.resolve(processor + OWNERSHIP_SUFFIX));
                    requireOwnedOrNew(srcJavaPackage, srcResourcePackage, pkg, processor);
                }
            }
            List<Path> stagedFiles = files(stagedResources);
            // Preflight every destination before the first write, including reuse builds.
            for (Path relative : stagedFiles) {
                if (!relative.getFileName().toString().endsWith(REUSED_MARKER_SUFFIX)) {
                    Path parent = relative.getParent();
                    if (parent != null) requireNoLinks(Paths.get(classesDirectory), parent.toString());
                    requireRegularOrAbsent(Paths.get(classesDirectory).resolve(relative));
                }
            }
            Counts classes = new Counts();
            for (Path relative : stagedFiles) {
                if (!relative.getFileName().toString().endsWith(REUSED_MARKER_SUFFIX)) {
                    promote(stagedResources.resolve(relative), Paths.get(classesDirectory).resolve(relative), classes);
                }
            }
            if (reuse) {
                getLog().info("Fluxtion promote: " + pkg + " reused (" + String.join(", ", reused)
                        + "), src/ unchanged; " + classes + " in " + classesDirectory);
                return;
            }
            Counts src = new Counts();
            for (String processor : known) {
                replace(stagedJavaPackage, srcJavaPackage, processor + ".java", src);
                for (String suffix : RESOURCE_SUFFIXES) {
                    replace(stagedResourcePackage, srcResourcePackage, processor + suffix, src);
                }
                // Last for this processor: an interrupted promotion cannot bless a partially written result.
                publishOwnership(srcJavaPackage, srcResourcePackage, pkg, processor, src);
            }
            getLog().info("Fluxtion promote: " + pkg + " generated (" + String.join(", ", staged) + "); src/ " + src
                    + ", " + src.deleted + " deleted; " + classes + " in " + classesDirectory);
        } catch (IOException e) {
            throw new MojoExecutionException("Fluxtion promote failed: " + e.getMessage(), e);
        }
    }

    /** A staged name alone does not establish ownership of an existing committed file. */
    private static void requireOwnedOrNew(Path javaDir, Path resourceDir, String pkg, String name)
            throws IOException, MojoExecutionException {
        boolean existing = Files.exists(javaDir.resolve(name + ".java"), LinkOption.NOFOLLOW_LINKS);
        for (String suffix : RESOURCE_SUFFIXES)
            existing |= Files.exists(resourceDir.resolve(name + suffix), LinkOption.NOFOLLOW_LINKS);
        Path ownership = resourceDir.resolve(name + OWNERSHIP_SUFFIX);
        existing |= Files.exists(ownership, LinkOption.NOFOLLOW_LINKS);
        if (!existing) return;
        if (Files.exists(ownership, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Map<String, Object> fields = Json.objectStrict(new String(Files.readAllBytes(ownership), StandardCharsets.UTF_8));
                Map<String, String> actual = ownedFiles(javaDir, resourceDir, name);
                if (fields.keySet().equals(new TreeSet<>(Arrays.asList("format", "processor", "files")))
                        && "fluxtion-promotion/1".equals(fields.get("format"))
                        && (pkg + "." + name).equals(fields.get("processor")) && !actual.isEmpty()
                        && actual.equals(fields.get("files"))) return;
            } catch (IllegalArgumentException invalid) {
                // A malformed marker cannot authorize replacement.
            }
            throw new MojoExecutionException("Fluxtion promote: files for " + pkg + "." + name
                    + " differ from their recorded promotion ownership at " + ownership
                    + "; preserve your edits or restore the previous generated files before retrying. Nothing promoted.");
        }
        Path stamp = resourceDir.resolve(name + STAMP_SUFFIX);
        if (Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Map<String, Object> fields = Json.objectStrict(new String(Files.readAllBytes(stamp), StandardCharsets.UTF_8));
                Object format = fields.get("format"), base = fields.get("relocatableBase");
                // V1 can prove prior ownership during upgrade; only the builder decides whether it can be reused.
                if (fields.keySet().equals(new TreeSet<>(Arrays.asList("format", "processor", "requestDigest",
                        "sourceDigest", "signed", "relocatableBase")))
                        && ("fluxtion-request-digest/1".equals(format) || "fluxtion-request-digest/2".equals(format))
                        && (pkg + "." + name).equals(fields.get("processor"))
                        && digest(fields.get("requestDigest")) && digest(fields.get("sourceDigest"))
                        && fields.get("signed") instanceof Boolean && (base == null || base instanceof String)) return;
            } catch (IllegalArgumentException invalid) {
                // An unreadable or unrelated file is not evidence of generator ownership.
            }
        }
        if (!Files.exists(stamp, LinkOption.NOFOLLOW_LINKS)) {
            List<Path> legacyFiles = unstampedGeneratedFiles(javaDir, resourceDir, name);
            if (!legacyFiles.isEmpty()) {
                throw new MojoExecutionException("Fluxtion promote: existing files for " + pkg + "." + name
                        + " have no committed ownership stamp; refusing to overwrite or delete them. These files"
                        + " look like a processor generated before 1.0.87, which did not stamp its output."
                        + " To adopt them, move these files out of src/, then build again (or out of your configured"
                        + " source/resource directories if different):\n  - "
                        + legacyFiles.stream().map(Path::toString).collect(Collectors.joining("\n  - "))
                        + "\nKeep a backup and review any edits before moving them. Nothing promoted.");
            }
        }
        throw new MojoExecutionException("Fluxtion promote: existing files for " + pkg + "." + name
                + " have no valid committed ownership stamp at " + stamp
                + "; refusing to overwrite or delete them. Move or rename the conflicting files, or restore"
                + " their original stamp if they were generated. Nothing promoted.");
    }

    /**
     * A generated header is a recovery hint, never ownership evidence. Require it on every conflicting Java
     * copy, not merely the expected filename/package; the matching GraphML/PNG companions may then be listed.
     * No hint is given for graph-only conflicts or for a present but invalid stamp/ownership marker.
     */
    private static List<Path> unstampedGeneratedFiles(Path javaDir, Path resourceDir, String name)
            throws IOException {
        List<Path> conflicts = new ArrayList<>();
        boolean generatedJava = false;
        List<Path> candidates = new ArrayList<>();
        candidates.add(javaDir.resolve(name + ".java"));
        for (String suffix : RESOURCE_SUFFIXES) candidates.add(resourceDir.resolve(name + suffix));
        for (Path file : candidates) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (file.getFileName().toString().endsWith(".java")) {
                try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String first = reader.readLine();
                    if (first == null || !first.replaceFirst("^\uFEFF", "").trim()
                            .startsWith("// Generated by Fluxtion.")) return Collections.emptyList();
                }
                generatedJava = true;
            }
            conflicts.add(file);
        }
        return generatedJava ? conflicts : Collections.emptyList();
    }

    /** Source/resource hashes make a previously promoted file's subsequent user edits visible. */
    private static Map<String, String> ownedFiles(Path javaDir, Path resourceDir, String name) throws IOException {
        Map<String, String> files = new TreeMap<>();
        Path source = javaDir.resolve(name + ".java");
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) files.put("source/" + source.getFileName(), contentHash(source));
        for (String suffix : RESOURCE_SUFFIXES) {
            Path file = resourceDir.resolve(name + suffix);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.put("resources/" + file.getFileName(), contentHash(file));
        }
        return files;
    }

    private static String contentHash(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // All promoted files are text (PNGs are removed); Git's CRLF conversion is not a user edit.
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            bytes = text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (java.nio.charset.CharacterCodingException binary) {
            // Non-UTF8 content retains its byte identity.
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void publishOwnership(Path javaDir, Path resourceDir, String pkg, String name, Counts counts) throws IOException {
        Map<String, String> files = ownedFiles(javaDir, resourceDir, name);
        Path destination = resourceDir.resolve(name + OWNERSHIP_SUFFIX);
        if (files.isEmpty()) {
            Files.deleteIfExists(destination);
            return;
        }
        StringBuilder json = new StringBuilder("{\n  \"format\": \"fluxtion-promotion/1\",\n  \"processor\": ")
                .append(Json.quote(pkg + "." + name)).append(",\n  \"files\": {");
        int at = 0;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            json.append(at++ == 0 ? "\n" : ",\n").append("    ").append(Json.quote(entry.getKey())).append(": ").append(Json.quote(entry.getValue()));
        }
        json.append("\n  }\n}\n");
        promoteBytes(json.toString().getBytes(StandardCharsets.UTF_8), destination, counts);
    }

    private static boolean digest(Object value) {
        return value instanceof String && ((String) value).matches("[0-9a-f]{64}");
    }

    /**
     * Refuses a symbolic link on the way from {@code root} down to the generated package: writing through one would
     * land outside the project's source tree. {@code root} itself may sit below a link (e.g. {@code /tmp}).
     */
    private static void requireNoLinks(Path root, String packagePath) throws MojoExecutionException {
        Path at = root.toAbsolutePath().normalize();
        for (Path part : Paths.get(packagePath)) {
            at = at.resolve(part);
            if (Files.isSymbolicLink(at)) {
                throw new MojoExecutionException("Fluxtion promote: " + at + " is a symbolic link; the generated"
                        + " package is not written through links. Nothing promoted.");
            }
            if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
        }
    }

    /**
     * Refuses a processor whose committed file is a link or not a file: skipping just that file would promote the
     * rest of the processor (a new stamp beside the old source), and the regenerated processor would silently never
     * land.
     */
    private static void requireRegularOrAbsent(Path to) throws MojoExecutionException {
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)) {
            throw new MojoExecutionException("Fluxtion promote: " + to + " is a symbolic link or not a regular file,"
                    + " so the processor cannot be replaced. Replace it with the file itself and rebuild. Nothing"
                    + " promoted.");
        }
    }

    /** One generator file: promoted when staged (PNG never), deleted when not staged. Checked to be a file first. */
    private static void replace(Path stagedDir, Path srcDir, String name, Counts counts) throws IOException {
        Path from = stagedDir.resolve(name);
        Path to = srcDir.resolve(name);
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
        promoteBytes(Files.readAllBytes(from), to, counts);
    }

    private static void promoteBytes(byte[] bytes, Path to, Counts counts) throws IOException {
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
