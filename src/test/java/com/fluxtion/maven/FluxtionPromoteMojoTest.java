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

    @Test(expected = MojoExecutionException.class)
    public void aGeneratedPackageIsRequired() throws Exception {
        mojo.generatedPackage = " ";
        mojo.execute();
    }
}
