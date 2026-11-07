package com.fluxtion.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.stream.Stream;

public class FluxtionPromoteMojoTest {

    private static final String PKG = "com/acme/demo/generated";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FluxtionPromoteMojo mojo;
    private Path stagingJava;
    private Path stagingResources;
    private Path srcJava;
    private Path srcResources;
    private Path classes;

    @Before
    public void setUp() throws IOException {
        Path root = tmp.getRoot().toPath();
        stagingJava = root.resolve("target/fluxtion-staging/java");
        stagingResources = root.resolve("target/fluxtion-staging/resources");
        srcJava = root.resolve("src/main/java");
        srcResources = root.resolve("src/main/resources");
        classes = root.resolve("target/classes");
        mojo = new FluxtionPromoteMojo();
        mojo.generatedPackage = "com.acme.demo.generated";
        mojo.stagingSourceDirectory = stagingJava.toString();
        mojo.stagingResourcesDirectory = stagingResources.toString();
        mojo.sourceDirectory = srcJava.toString();
        mojo.resourcesDirectory = srcResources.toString();
        mojo.classesDirectory = classes.toString();
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(PKG).resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String stamp(String processor) {
        return "{\"format\":\"fluxtion-request-digest/1\",\"processor\":\"com.acme.demo.generated." + processor
            + "\",\"requestDigest\":\"" + String.join("", java.util.Collections.nCopies(64, "a"))
            + "\",\"sourceDigest\":\"" + String.join("", java.util.Collections.nCopies(64, "b"))
            + "\",\"signed\":false,\"relocatableBase\":null}";
    }

    private void commit() throws IOException {
        write(srcJava, "DemoProcessor.java", "committed source");
        write(srcResources, "DemoProcessor.graphml", "committed graphml");
        write(srcResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
    }

    @Test
    public void reuseLeavesSrcUntouchedAndStillFillsClasses() throws Exception {
        commit();
        write(stagingJava, "DemoProcessor.java", "committed source");
        write(stagingResources, "DemoProcessor.graphml", "regenerated graphml");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
        Path committedSource = srcJava.resolve(PKG).resolve("DemoProcessor.java");
        FileTime before = Files.getLastModifiedTime(committedSource);

        mojo.execute();

        Assert.assertEquals("committed graphml", read(srcResources.resolve(PKG).resolve("DemoProcessor.graphml")));
        Assert.assertEquals(before, Files.getLastModifiedTime(committedSource));
        Assert.assertEquals("regenerated graphml", read(classes.resolve(PKG).resolve("DemoProcessor.graphml")));
        Assert.assertEquals(stamp("DemoProcessor"), read(classes.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertFalse(Files.exists(classes.resolve(PKG).resolve("DemoProcessor.fluxtion-reused")));
        Assert.assertFalse(Files.exists(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-reused")));
    }

    @Test
    public void generateReplacesChangedSkipsUnchangedDeletesStaleAndNeverPromotesPng() throws Exception {
        commit();
        write(srcJava, "OldProcessor.java", "renamed away");
        write(srcResources, "DemoProcessor.png", "old picture");
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.graphml", "committed graphml");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        write(stagingResources, "DemoProcessor.png", "picture");
        Path unchanged = srcResources.resolve(PKG).resolve("DemoProcessor.graphml");
        FileTime before = Files.getLastModifiedTime(unchanged);
        Thread.sleep(20);

        mojo.execute();

        Path pkg = srcJava.resolve(PKG);
        Assert.assertEquals("new source", read(pkg.resolve("DemoProcessor.java")));
        Assert.assertTrue("not the generator's (no stamp), so kept", Files.exists(pkg.resolve("OldProcessor.java")));
        Assert.assertEquals(stamp("DemoProcessor"), read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertEquals("unchanged bytes are not rewritten", before, Files.getLastModifiedTime(unchanged));
        Assert.assertFalse("PNG is never promoted to src", Files.exists(srcResources.resolve(PKG).resolve("DemoProcessor.png")));
        Assert.assertEquals("picture", read(classes.resolve(PKG).resolve("DemoProcessor.png")));
        Assert.assertEquals("[DemoProcessor.java, OldProcessor.java]", FluxtionPromoteMojo.files(pkg).toString());
    }

    @Test
    public void aPartlyReusedPackageIsPromotedAndTheReusedProcessorKeepsItsBytes() throws Exception {
        commit();
        write(srcJava, "OtherProcessor.java", "other committed");
        write(srcResources, "OtherProcessor.fluxtion-request.json", stamp("OtherProcessor"));
        write(stagingJava, "DemoProcessor.java", "committed source");
        write(stagingJava, "OtherProcessor.java", "other regenerated");
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
        write(stagingResources, "DemoProcessor.graphml", "committed graphml");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));

        mojo.execute();

        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals("other regenerated", read(srcJava.resolve(PKG).resolve("OtherProcessor.java")));
        Assert.assertEquals(stamp("DemoProcessor"), read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
    }

    @Test
    public void nothingStagedIsRefusedAndSrcIsUnchanged() throws Exception {
        commit();
        try {
            mojo.execute();
            Assert.fail("expected a refusal");
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("staging holds no processor"));
        }
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals("committed graphml", read(srcResources.resolve(PKG).resolve("DemoProcessor.graphml")));
    }

    private static Path put(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** Review witness M1(a): the base package named as the generated package must not delete hand-written sources. */
    @Test
    public void basePackageAsGeneratedPackageNeverDeletesUserSources() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path user = put(root.resolve("src/main/java/com/acme/app/node/Handler.java"), "user code");
        put(root.resolve("src/main/java/com/acme/app/generated/P.java"), "committed");
        put(root.resolve("target/fluxtion-staging/java/com/acme/app/generated/P.java"), "committed");
        put(root.resolve("target/fluxtion-staging/resources/com/acme/app/generated/P.fluxtion-reused"), "x");
        mojo.generatedPackage = "com.acme.app";
        try {
            mojo.execute();
            Assert.fail("expected a refusal: no processor directly in com.acme.app");
        } catch (MojoExecutionException expected) {
            // refused, nothing deleted
        }
        Assert.assertTrue("hand-written source deleted by promote", Files.exists(user));
    }

    /** Review witness M1(b): a hand-written file in a sub-package of the generated package is kept. */
    @Test
    public void subPackageOfGeneratedPackageIsKept() throws Exception {
        Path user = put(srcJava.resolve(PKG).resolve("support/Helper.java"), "user code");
        write(srcJava, "DemoProcessor.java", "old");
        write(srcResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        write(stagingJava, "DemoProcessor.java", "new");
        mojo.execute();
        Assert.assertTrue("sub-package user file deleted by promote", Files.exists(user));
        Assert.assertEquals("new", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    /** Review witness M1(c): a package name that is not a Java package is refused before anything is touched. */
    @Test
    public void aPathAsGeneratedPackageIsRefused() throws Exception {
        Path root = tmp.getRoot().toPath();
        Path user = put(root.resolve("src/main/resources/application.yml"), "user config");
        put(root.resolve("target/fluxtion-staging/java/x/P.java"), "new");
        for (String bad : new String[]{"..", "../x", "com/acme", "com..acme", "/abs", "com.acme.", ".com", "1com"}) {
            mojo.generatedPackage = bad;
            try {
                mojo.execute();
                Assert.fail("expected refusal of " + bad);
            } catch (MojoExecutionException e) {
                Assert.assertTrue(e.getMessage(), e.getMessage().contains("<generatedPackage>"));
            }
        }
        Assert.assertTrue("file outside the generated package deleted", Files.exists(user));
        Assert.assertFalse(Files.exists(classes));
    }

    @Test
    public void onlyTheGeneratorsFilesAreReplacedOrDeleted() throws Exception {
        commit();
        Path handWritten = write(srcJava, "Notes.java", "hand-written, no stamp");
        Path readme = write(srcResources, "README.txt", "hand-written");
        // a renamed processor: committed with a stamp, no longer staged
        Path oldSource = write(srcJava, "OldProcessor.java", "old");
        Path oldGraph = write(srcResources, "OldProcessor.graphml", "old");
        Path oldStamp = write(srcResources, "OldProcessor.fluxtion-request.json", stamp("OldProcessor"));
        Path backup = write(srcJava, "OldProcessor.java.backup", "kept");
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));

        mojo.execute();

        Assert.assertTrue(Files.exists(handWritten));
        Assert.assertTrue(Files.exists(readme));
        Assert.assertTrue(Files.exists(backup));
        Assert.assertFalse(Files.exists(oldSource));
        Assert.assertFalse(Files.exists(oldGraph));
        Assert.assertFalse(Files.exists(oldStamp));
        Assert.assertEquals("new source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertFalse("not staged, so no longer generated",
                Files.exists(srcResources.resolve(PKG).resolve("DemoProcessor.graphml")));
    }

    @Test
    public void aReusedProcessorWithoutStagedSourceIsNeverDeleted() throws Exception {
        commit();
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
        write(stagingJava, "OtherProcessor.java", "other generated");
        mojo.execute();
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals(stamp("DemoProcessor"), read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertEquals("other generated", read(srcJava.resolve(PKG).resolve("OtherProcessor.java")));
    }

    @Test
    public void promotedFilesKeepOrGetNormalPermissions() throws Exception {
        org.junit.Assume.assumeTrue("POSIX permissions only",
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        commit();
        Path replaced = srcJava.resolve(PKG).resolve("DemoProcessor.java");
        Set<PosixFilePermission> custom = PosixFilePermissions.fromString("rwxr-x---");
        Files.setPosixFilePermissions(replaced, custom);
        // what an ordinary write in this directory gets under the current umask
        Path reference = write(srcResources, "reference.txt", "x");
        Set<PosixFilePermission> normal = Files.getPosixFilePermissions(reference);
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        write(stagingJava, "NewProcessor.java", "new processor");
        write(stagingResources, "NewProcessor.fluxtion-request.json", stamp("NewProcessor"));

        mojo.execute();

        Assert.assertEquals("new source", read(replaced));
        Assert.assertEquals("a replaced file keeps its permissions", custom, Files.getPosixFilePermissions(replaced));
        Path created = srcResources.resolve(PKG).resolve("NewProcessor.fluxtion-request.json");
        Assert.assertEquals(stamp("NewProcessor"), read(created));
        Assert.assertEquals("a new file gets the default permissions", normal, Files.getPosixFilePermissions(created));
        Assert.assertEquals("a new class file gets the default permissions", normal,
                Files.getPosixFilePermissions(classes.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
    }

    private static void link(Path link, Path target) {
        try {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException noLinks) {
            org.junit.Assume.assumeNoException(noLinks);
        }
    }

    private void assertRefusedNaming(Path named) {
        try {
            mojo.execute();
            Assert.fail("expected a refusal naming " + named);
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains(named.toString()));
        }
    }

    /** A renamed-away processor whose committed source is a link: refused, the link and its target untouched. */
    @Test
    public void aSymbolicLinkIsNeverFollowedOrDeleted() throws Exception {
        commit();
        Path outside = put(tmp.getRoot().toPath().resolve("outside/OldProcessor.java"), "outside");
        Path link = srcJava.resolve(PKG).resolve("OldProcessor.java");
        link(link, outside);
        write(srcResources, "OldProcessor.fluxtion-request.json", stamp("OldProcessor"));
        write(stagingJava, "DemoProcessor.java", "new source");
        assertRefusedNaming(link);
        Assert.assertTrue(Files.isSymbolicLink(link));
        Assert.assertEquals("outside", read(outside));
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    /** Review witness N1: a linked committed source must not leave a new stamp beside the old source. */
    @Test
    public void aLinkedProcessorSourceRefusesTheWholeProcessor() throws Exception {
        Path real = put(tmp.getRoot().toPath().resolve("elsewhere/DemoProcessor.java"), "old source");
        Path link = srcJava.resolve(PKG).resolve("DemoProcessor.java");
        link(link, real);
        write(srcResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        assertRefusedNaming(link);
        Assert.assertEquals("old source", read(real));
        Assert.assertEquals(stamp("DemoProcessor"), read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertFalse("refused before target/classes is written", Files.exists(classes));
    }

    /** Review witness N2: a linked package directory, or a linked ancestor below the source root, is refused. */
    @Test
    public void aLinkedPackageDirectoryOrAncestorIsRefused() throws Exception {
        write(stagingJava, "DemoProcessor.java", "new source");
        Path elsewhere = tmp.getRoot().toPath().resolve("elsewhere");
        Files.createDirectories(elsewhere);
        Path linkedPackage = srcJava.resolve(PKG);
        link(linkedPackage, elsewhere);
        assertRefusedNaming(linkedPackage);
        Assert.assertFalse(Files.exists(elsewhere.resolve("DemoProcessor.java")));

        Files.delete(linkedPackage);
        Path linkedAncestor = srcResources.resolve("com/acme");
        link(linkedAncestor, elsewhere);
        assertRefusedNaming(linkedAncestor);
        try (Stream<Path> left = Files.list(elsewhere)) {
            Assert.assertEquals("nothing written through the link", 0, left.count());
        }
    }

    /** A linked source root itself (e.g. under /tmp on macOS) is the project's own choice and is allowed. */
    @Test
    public void aLinkedSourceRootIsAllowed() throws Exception {
        Path realRoot = tmp.getRoot().toPath().resolve("real-src-java");
        Files.createDirectories(realRoot);
        Path linkedRoot = tmp.getRoot().toPath().resolve("linked-src-java");
        link(linkedRoot, realRoot);
        mojo.sourceDirectory = linkedRoot.toString();
        write(stagingJava, "DemoProcessor.java", "new source");
        mojo.execute();
        Assert.assertEquals("new source", read(realRoot.resolve(PKG).resolve("DemoProcessor.java")));
    }


    private static final String GENERATED_HEADER =
            "// Generated by Fluxtion. Regenerate this processor from its graph definition.\n";

    @Test
    public void unstampedGeneratedProcessorNamesMoveAsideRecoveryAndThenPromotes() throws Exception {
        Path java = write(srcJava, "DemoProcessor.java", GENERATED_HEADER + "old generated source");
        Path resourceJava = write(srcResources, "DemoProcessor.java", GENERATED_HEADER + "old generated source");
        Path graph = write(srcResources, "DemoProcessor.graphml", "old graph");
        Path png = write(srcResources, "DemoProcessor.png", "old picture");
        Path notes = write(srcJava, "Notes.java", "hand-written companion");
        write(stagingJava, "DemoProcessor.java", GENERATED_HEADER + "new generated source");
        write(stagingResources, "DemoProcessor.graphml", "new graph");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        try {
            mojo.execute();
            Assert.fail("an apparent generated header must not authorize replacement");
        } catch (MojoExecutionException expected) {
            String message = expected.getMessage();
            Assert.assertTrue(message, message.contains("generated before 1.0.87"));
            Assert.assertTrue(message, message.contains("move these files out of src/, then build again"));
            for (Path conflict : new Path[]{java, resourceJava, graph, png})
                Assert.assertTrue(message, message.contains(conflict.toString()));
            Assert.assertFalse(message, message.contains(notes.toString()));
        }
        Assert.assertEquals(GENERATED_HEADER + "old generated source", read(java));
        Assert.assertFalse(Files.exists(classes));
        Path backup = tmp.getRoot().toPath().resolve("backup");
        Files.createDirectories(backup);
        for (Path conflict : new Path[]{java, resourceJava, graph, png})
            Files.move(conflict, backup.resolve((conflict.startsWith(srcJava) ? "source-" : "resource-")
                    + conflict.getFileName()));
        mojo.execute();
        Assert.assertEquals(GENERATED_HEADER + "new generated source", read(java));
        Assert.assertEquals("hand-written companion", read(notes));
        Assert.assertTrue(Files.isRegularFile(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertTrue(Files.isRegularFile(srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX)));
    }

    @Test
    public void handwrittenSourceDoesNotGetLegacyGeneratedHintEvenWithGeneratedResourceCopy() throws Exception {
        Path user = write(srcJava, "DemoProcessor.java", "package com.acme.demo.generated; class DemoProcessor {}");
        write(srcResources, "DemoProcessor.java", GENERATED_HEADER + "generated resource copy");
        write(srcResources, "DemoProcessor.graphml", "graph");
        write(stagingJava, "DemoProcessor.java", "new generated processor");
        assertNoUpgradeHint();
        Assert.assertEquals("package com.acme.demo.generated; class DemoProcessor {}", read(user));
    }

    @Test
    public void handwrittenResourceCopyDoesNotGetLegacyGeneratedHint() throws Exception {
        write(srcJava, "DemoProcessor.java", GENERATED_HEADER + "generated source");
        write(srcResources, "DemoProcessor.java", "hand-written resource");
        write(stagingJava, "DemoProcessor.java", "new generated processor");
        assertNoUpgradeHint();
    }

    @Test
    public void malformedExistingStampDoesNotGetLegacyGeneratedHint() throws Exception {
        write(srcJava, "DemoProcessor.java", GENERATED_HEADER + "generated source");
        write(stagingJava, "DemoProcessor.java", "new generated processor");
        write(srcResources, "DemoProcessor.fluxtion-request.json", "malformed");
        assertNoUpgradeHint();
    }

    @Test
    public void malformedExistingOwnershipDoesNotGetLegacyGeneratedHint() throws Exception {
        write(srcJava, "DemoProcessor.java", GENERATED_HEADER + "generated source");
        write(stagingJava, "DemoProcessor.java", "new generated processor");
        write(srcResources, "DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX, "malformed");
        assertNoUpgradeHint();
    }

    private void assertNoUpgradeHint() throws Exception {
        try { mojo.execute(); Assert.fail("must refuse unowned files"); }
        catch (MojoExecutionException expected) {
            Assert.assertFalse(expected.getMessage(), expected.getMessage().contains("generated before 1.0.87"));
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("Nothing promoted"));
        }
        Assert.assertFalse(Files.exists(classes));
    }

    @Test
    public void stagedNameCannotOverwriteUnstampedUserSource() throws Exception {
        Path user = write(srcJava, "Notes.java", "hand-written user source");
        write(stagingJava, "Notes.java", "new generated processor");
        write(stagingResources, "Notes.fluxtion-request.json", stamp("Notes"));
        assertRefusedNaming(srcResources.resolve(PKG).resolve("Notes.fluxtion-request.json"));
        Assert.assertEquals("hand-written user source", read(user));
        Assert.assertFalse(Files.exists(classes));
    }

    @Test
    public void malformedOrWrongOwnerStampCannotAuthorizeReplacement() throws Exception {
        Path user = write(srcJava, "DemoProcessor.java", "user source");
        write(stagingJava, "DemoProcessor.java", "new generated source");
        for (String invalid : new String[]{"not json", stamp("SomeoneElse"),
                stamp("DemoProcessor").replace("digest/1", "digest/999"),
                stamp("DemoProcessor").replace("{", "{\"signed\":true,"),
                stamp("DemoProcessor").replace("com.acme", "com\\q.acme")}) {
            Path record = write(srcResources, "DemoProcessor.fluxtion-request.json", invalid);
            assertRefusedNaming(record);
            Assert.assertEquals("user source", read(user));
            Assert.assertFalse(Files.exists(classes));
        }
    }

    @Test
    public void previousAndCurrentStampFormatsPermitOwnedReplacement() throws Exception {
        for (String version : new String[]{"1", "2"}) {
            Files.deleteIfExists(srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX));
            write(srcJava, "DemoProcessor.java", "old generated");
            write(srcResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor").replace("digest/1", "digest/" + version));
            write(stagingJava, "DemoProcessor.java", "new generated");
            mojo.execute();
            Assert.assertEquals("new generated", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        }
    }

    @Test
    public void linkedClassesPackageRefusesGenerationAndReuseBeforeWriting() throws Exception {
        commit();
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.graphml", "new graph");
        Path outside = tmp.getRoot().toPath().resolve("external");
        Path victim = put(outside.resolve("DemoProcessor.graphml"), "external user bytes");
        Path linked = classes.resolve(PKG);
        link(linked, outside);
        for (boolean reused : new boolean[]{false, true}) {
            if (reused) write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
            assertRefusedNaming(linked);
            Assert.assertEquals("external user bytes", read(victim));
            Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        }
    }

    @Test
    public void linkedClassesFileRefusesBeforeAnyResourceCopy() throws Exception {
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.graphml", "new graph");
        Path outside = put(tmp.getRoot().toPath().resolve("outside.graphml"), "external");
        Path linked = classes.resolve(PKG).resolve("DemoProcessor.graphml");
        link(linked, outside);
        assertRefusedNaming(linked);
        Assert.assertEquals("external", read(outside));
        Assert.assertFalse(Files.exists(srcJava));
    }


    @Test
    public void unstampedCustomTemplateOutputCanBePromotedRepeatedly() throws Exception {
        write(stagingJava, "Custom.java", "generated answer 1");
        mojo.execute();
        Path ownership = srcResources.resolve(PKG).resolve("Custom" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX);
        Assert.assertTrue(Files.isRegularFile(ownership));
        Assert.assertFalse(Files.exists(srcResources.resolve(PKG).resolve("Custom.fluxtion-request.json")));
        write(stagingJava, "Custom.java", "generated answer 2");
        mojo.execute();
        Assert.assertEquals("generated answer 2", read(srcJava.resolve(PKG).resolve("Custom.java")));
    }

    @Test
    public void userEditAfterPromotionRefusesEvenWithAValidRequestStamp() throws Exception {
        write(stagingJava, "DemoProcessor.java", "generated source");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
        mojo.execute();
        Path user = write(srcJava, "DemoProcessor.java", "user edit after promotion");
        Path marker = srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX);
        write(stagingJava, "DemoProcessor.java", "new generation");
        assertRefusedNaming(marker);
        Assert.assertEquals("user edit after promotion", read(user));
    }

    @Test
    public void aFailureBeforeTheCommitPointLeavesSrcWhollyOld() throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        write(stagingJava, "Custom.java", "first generation");
        mojo.execute();
        Path directory = srcResources.resolve(PKG);
        Path marker = directory.resolve("Custom" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX);
        byte[] priorMarker = Files.readAllBytes(marker);
        Set<PosixFilePermission> previous = Files.getPosixFilePermissions(directory);
        write(stagingJava, "Custom.java", "second generation");
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            try { mojo.execute(); Assert.fail("marker publication must fail"); }
            catch (MojoExecutionException expected) { Assert.assertTrue(expected.getMessage().contains("promote failed")); }
        } finally { Files.setPosixFilePermissions(directory, previous); }
        Assert.assertArrayEquals(priorMarker, Files.readAllBytes(marker));
        // 1.3.4: every file is prepared before any is replaced, so the failure leaves the first generation whole
        // (1.3.3 had already replaced the source, and the next build refused the half-promoted package)
        Assert.assertEquals("first generation", read(srcJava.resolve(PKG).resolve("Custom.java")));
        try (Stream<Path> files = Files.list(srcJava.resolve(PKG))) {
            Assert.assertEquals("no temporaries left in src/", 1L, files.count());
        }
        mojo.execute();
        Assert.assertEquals("second generation", read(srcJava.resolve(PKG).resolve("Custom.java")));
    }

    @Test
    public void renamedCustomProcessorDeletesOnlyItsUneditedOwnedFiles() throws Exception {
        write(stagingJava, "Custom.java", "first generation");
        mojo.execute();
        Files.delete(stagingJava.resolve(PKG).resolve("Custom.java"));
        write(stagingJava, "Renamed.java", "next generation");
        Path user = write(srcJava, "Notes.java", "user notes");
        mojo.execute();
        Assert.assertFalse(Files.exists(srcJava.resolve(PKG).resolve("Custom.java")));
        Assert.assertFalse(Files.exists(srcResources.resolve(PKG).resolve("Custom" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX)));
        Assert.assertEquals("user notes", read(user));
    }

    @Test
    public void lineEndingConversionDoesNotInvalidatePromotionOwnership() throws Exception {
        write(stagingJava, "Custom.java", "first generation\n");
        mojo.execute();
        write(srcJava, "Custom.java", "first generation\r\n");
        write(stagingJava, "Custom.java", "next generation\n");
        mojo.execute();
        Assert.assertEquals("next generation\n", read(srcJava.resolve(PKG).resolve("Custom.java")));
    }

    @Test
    public void malformedPromotionOwnershipDoesNotFallBackToRequestStamp() throws Exception {
        commit();
        Path marker = write(srcResources, "DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX, "{broken");
        write(stagingJava, "DemoProcessor.java", "new generated");
        assertRefusedNaming(marker);
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    @Test(expected = MojoExecutionException.class)
    public void aGeneratedPackageIsRequired() throws Exception {
        mojo.generatedPackage = " ";
        mojo.execute();
    }

    // ---- 1.3.4: deferral to the tests, stale output, reachability metadata, recovery ----------------------

    private org.apache.maven.execution.MavenSession sessionWithGoals(String... goals) {
        org.apache.maven.execution.MavenSession session = org.mockito.Mockito.mock(org.apache.maven.execution.MavenSession.class);
        org.mockito.Mockito.when(session.getGoals()).thenReturn(java.util.Arrays.asList(goals));
        return session;
    }

    private void stageGeneration(String source) throws IOException {
        write(stagingJava, "DemoProcessor.java", source);
        write(stagingResources, "DemoProcessor.graphml", "graph of " + source);
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp("DemoProcessor"));
    }

    @Test
    public void deferToTestsLeavesSrcForTheTestPhaseExecution() throws Exception {
        commit();
        stageGeneration("untested source");
        mojo.deferToTests = true;
        mojo.session = sessionWithGoals("clean", "package");
        mojo.execute();
        Assert.assertEquals("src/ waits for the tests", "committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals("target/classes has the new processor's resources for the tests",
                "graph of untested source", read(classes.resolve(PKG).resolve("DemoProcessor.graphml")));
        FluxtionPromoteMojo tested = new FluxtionPromoteMojo();
        tested.generatedPackage = mojo.generatedPackage;
        tested.stagingSourceDirectory = mojo.stagingSourceDirectory;
        tested.stagingResourcesDirectory = mojo.stagingResourcesDirectory;
        tested.sourceDirectory = mojo.sourceDirectory;
        tested.resourcesDirectory = mojo.resourcesDirectory;
        tested.classesDirectory = mojo.classesDirectory;
        tested.afterTests = true;
        tested.execute();
        Assert.assertEquals("untested source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertFalse(Files.exists(tmp.getRoot().toPath().resolve("target").resolve(FluxtionPromoteMojo.DEFERRED_MARKER)));
        tested.execute(); // nothing held back now: a no-op
        Assert.assertEquals("untested source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    @Test
    public void deferToTestsPromotesAtOnceWhenTheBuildStopsBeforeTheTests() throws Exception {
        commit();
        stageGeneration("compiled source");
        mojo.deferToTests = true;
        for (String goal : new String[]{"compile", "test-compile"}) {
            mojo.session = sessionWithGoals(goal);
            Assert.assertFalse(goal, mojo.testsWillRun());
        }
        mojo.session = sessionWithGoals("compile");
        mojo.execute();
        Assert.assertEquals("compiled source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        mojo.session = sessionWithGoals("verify");
        Assert.assertTrue(mojo.testsWillRun());
    }

    @Test
    public void aRenamedProcessorsStaleOutputLeavesTargetClasses() throws Exception {
        // OldProcessor is a retired processor: an earlier promotion copied its stamp into target/classes
        write(classes, "OldProcessor.class", "old");
        write(classes, "OldProcessor$1.class", "old inner");
        write(classes, "OldProcessor.graphml", "old graph");
        write(classes, "OldProcessor.fluxtion-request.json", "old stamp");
        // copied from src/main/resources by process-resources after a killed promotion, before recovery ran
        write(classes, PromotionJournal.JOURNAL, "{}");
        write(classes, ".OldProcessor.graphml.11111111-2222-3333-4444-555555555555.promote", "half");
        Path oldMetadata = classes.resolve("META-INF/native-image/com.acme.demo.generated.OldProcessor/reachability-metadata.json");
        Files.createDirectories(oldMetadata.getParent());
        Files.write(oldMetadata, "{}".getBytes(StandardCharsets.UTF_8));
        Path liveMetadata = classes.resolve("META-INF/native-image/com.acme.demo.generated.DemoProcessor/reachability-metadata.json");
        Files.createDirectories(liveMetadata.getParent());
        Files.write(liveMetadata, "{}".getBytes(StandardCharsets.UTF_8));
        mojo.compileSourceRoots = java.util.Collections.singletonList(srcJava.toString());
        stageGeneration("new source");
        mojo.execute();
        for (String gone : new String[]{"OldProcessor.class", "OldProcessor$1.class", "OldProcessor.graphml",
                "OldProcessor.fluxtion-request.json", PromotionJournal.JOURNAL,
                ".OldProcessor.graphml.11111111-2222-3333-4444-555555555555.promote"}) {
            Assert.assertFalse(gone, Files.exists(classes.resolve(PKG).resolve(gone)));
        }
        Assert.assertFalse(Files.exists(oldMetadata.getParent()));
        Assert.assertTrue("the live processor's metadata stays", Files.exists(liveMetadata));
    }

    /**
     * Review finding 3: user output in the generated package is never stale output. A second top-level class in a user
     * source (Util.java declaring UtilHelper), a class from another language's source root or a bytecode generator
     * (no .java anywhere), and a user native-image directory under the package prefix all have no generator record.
     */
    @Test
    public void userClassesInTheGeneratedPackageAreNeverRemoved() throws Exception {
        write(srcJava, "Util.java", "package com.acme.demo.generated; public class Util {} class UtilHelper {}");
        for (String user : new String[]{"Util.class", "UtilHelper.class", "Util$1.class", "KotlinThing.class",
                "Generated$$Proxy.class", "notes.txt"}) {
            write(classes, user, "user");
        }
        Path userMetadata = classes.resolve("META-INF/native-image/com.acme.demo.generated.Custom/native-image.properties");
        Files.createDirectories(userMetadata.getParent());
        Files.write(userMetadata, "Args = -H:+User".getBytes(StandardCharsets.UTF_8));
        mojo.compileSourceRoots = java.util.Arrays.asList(srcJava.toString(), stagingJava.toString());
        stageGeneration("new source");
        mojo.execute();
        for (String user : new String[]{"Util.class", "UtilHelper.class", "Util$1.class", "KotlinThing.class",
                "Generated$$Proxy.class", "notes.txt"}) {
            Assert.assertTrue(user, Files.exists(classes.resolve(PKG).resolve(user)));
        }
        Assert.assertTrue(Files.exists(userMetadata));
    }

    /**
     * The stamped metadata is promoted with the processor, but the ownership marker records exactly what 1.3.3 records
     * (review finding 2): a 1.3.3 promote after this one recomputes the same map, so it does not refuse.
     */
    @Test
    public void stampedReachabilityMetadataIsPromotedButNotInTheOwnershipMap() throws Exception {
        String stamp = stamp("DemoProcessor").replace("\"signed\":false", "\"signed\":false,\"reachabilityMetadataDigest\":\""
                + String.join("", java.util.Collections.nCopies(64, "c")) + "\"");
        write(srcJava, "DemoProcessor.java", "committed source");
        write(srcResources, "DemoProcessor.fluxtion-request.json", stamp);
        write(srcResources, "DemoProcessor" + FluxtionPromoteMojo.REACHABILITY_SUFFIX, "old metadata");
        write(stagingJava, "DemoProcessor.java", "new source");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", stamp);
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REACHABILITY_SUFFIX, "new metadata");
        mojo.execute(); // the optional key is accepted as ownership evidence
        Assert.assertEquals("new metadata", read(srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.REACHABILITY_SUFFIX)));
        String owned = read(srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.OWNERSHIP_SUFFIX));
        Assert.assertFalse(owned, owned.contains(FluxtionPromoteMojo.REACHABILITY_SUFFIX));
        Assert.assertTrue(owned, owned.contains("\"source/DemoProcessor.java\"") && owned.contains("resources/DemoProcessor.fluxtion-request.json"));
        // the flag turned off: the next generation stages no metadata, so the committed copy goes
        Files.delete(stagingResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.REACHABILITY_SUFFIX));
        write(stagingJava, "DemoProcessor.java", "flag off source");
        mojo.execute();
        Assert.assertFalse(Files.exists(srcResources.resolve(PKG).resolve("DemoProcessor" + FluxtionPromoteMojo.REACHABILITY_SUFFIX)));
    }

    // ---- review finding 4: the test gate ---------------------------------------------------------------

    private FluxtionPromoteMojo afterTestsMojo() {
        FluxtionPromoteMojo tested = new FluxtionPromoteMojo();
        tested.generatedPackage = mojo.generatedPackage;
        tested.stagingSourceDirectory = mojo.stagingSourceDirectory;
        tested.stagingResourcesDirectory = mojo.stagingResourcesDirectory;
        tested.sourceDirectory = mojo.sourceDirectory;
        tested.resourcesDirectory = mojo.resourcesDirectory;
        tested.classesDirectory = mojo.classesDirectory;
        tested.afterTests = true;
        return tested;
    }

    private void deferOneGeneration(String source) throws Exception {
        commit();
        stageGeneration(source);
        mojo.deferToTests = true;
        mojo.session = sessionWithGoals("package");
        mojo.execute();
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        // the module has tests
        Path testClass = tmp.getRoot().toPath().resolve("target/test-classes/t/ATest.class");
        Files.createDirectories(testClass.getParent());
        Files.write(testClass, new byte[]{1});
    }

    private void report(int failures) throws IOException {
        Path report = tmp.getRoot().toPath().resolve("target/surefire-reports/TEST-t.ATest.xml");
        Files.createDirectories(report.getParent());
        Files.write(report, ("<?xml version=\"1.0\"?>\n<testsuite name=\"t.ATest\" tests=\"2\" errors=\"0\" skipped=\"0\" failures=\""
                + failures + "\"></testsuite>\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void afterTestsRefusesWhenTheTestsHaveNotRunYet() throws Exception {
        deferOneGeneration("untested source");
        try {
            afterTestsMojo().execute();
            Assert.fail("an afterTests execution ordered before surefire must refuse");
        } catch (MojoExecutionException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("declare maven-surefire-plugin before"));
        }
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    @Test
    public void afterTestsPromotesWhenThisBuildsReportsPass() throws Exception {
        deferOneGeneration("tested source");
        report(0);
        afterTestsMojo().execute();
        Assert.assertEquals("tested source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    @Test
    public void ignoredTestFailuresDoNotPromote() throws Exception {
        deferOneGeneration("failing source");
        report(1); // reached only with maven.test.failure.ignore / testFailureIgnore
        RecordingLog log = new RecordingLog();
        FluxtionPromoteMojo tested = afterTestsMojo();
        tested.setLog(log);
        tested.execute();
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertTrue(log.lines.toString(), log.lines.stream().anyMatch(l -> l.contains("NOT promoted")));
        Assert.assertEquals(1, TestGate.failures("<testsuite failures=\"1\" errors=\"0\">"));
        Assert.assertEquals(3, TestGate.failures("<testsuite errors=\"2\"\n failures=\"1\">"));
    }

    @Test
    public void skippedTestsPromoteAtCompileWithAWarning() throws Exception {
        commit();
        stageGeneration("untested source");
        mojo.deferToTests = true;
        mojo.session = sessionWithGoals("package");
        java.util.Properties user = new java.util.Properties();
        user.setProperty("skipTests", "true");
        org.mockito.Mockito.when(mojo.session.getUserProperties()).thenReturn(user);
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);
        mojo.execute();
        Assert.assertEquals("untested source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertTrue(log.lines.toString(), log.lines.stream().anyMatch(l -> l.contains("UNTESTED")));
    }

    @Test
    public void aDeferredMarkerThatOutlivedItsBuildIsReported() throws Exception {
        deferOneGeneration("held back source");
        // the build stopped (failed tests): the next build's compile-phase promote reports it
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);
        mojo.deferToTests = false;
        stageGeneration("next source");
        mojo.execute();
        Assert.assertTrue(log.lines.toString(), log.lines.stream().anyMatch(l -> l.contains("never promoted it into src/")));
    }

    @Test
    public void promoteFirstCompletesAnInterruptedPromotion() throws Exception {
        commit();
        Path res = srcResources.resolve(PKG);
        String tx = "11111111-2222-3333-4444-555555555555";
        Files.write(res.resolve(".DemoProcessor.graphml." + tx + ".promote"), "half promoted graph".getBytes(StandardCharsets.UTF_8));
        Files.write(res.resolve(PromotionJournal.JOURNAL), ("{\"format\": \"" + PromotionJournal.FORMAT + "\", \"tx\": \""
                + tx + "\", \"ops\": [{\"path\": \"DemoProcessor.graphml\", \"temp\": \".DemoProcessor.graphml." + tx
                + ".promote\", \"sha256\": \"" + PromotionJournal.sha256("half promoted graph".getBytes(StandardCharsets.UTF_8))
                + "\"}]}").getBytes(StandardCharsets.UTF_8));
        write(stagingJava, "DemoProcessor.java", "committed source");
        write(stagingResources, "DemoProcessor.fluxtion-reused", "x");
        mojo.execute();
        Assert.assertEquals("half promoted graph", read(res.resolve("DemoProcessor.graphml")));
        Assert.assertFalse(Files.exists(res.resolve(PromotionJournal.JOURNAL)));
    }
}
