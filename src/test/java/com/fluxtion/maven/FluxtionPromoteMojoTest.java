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

    private void commit() throws IOException {
        write(srcJava, "DemoProcessor.java", "committed source");
        write(srcResources, "DemoProcessor.graphml", "committed graphml");
        write(srcResources, "DemoProcessor.fluxtion-request.json", "committed stamp");
    }

    @Test
    public void reuseLeavesSrcUntouchedAndStillFillsClasses() throws Exception {
        commit();
        write(stagingJava, "DemoProcessor.java", "committed source");
        write(stagingResources, "DemoProcessor.graphml", "regenerated graphml");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", "committed stamp");
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
        Path committedSource = srcJava.resolve(PKG).resolve("DemoProcessor.java");
        FileTime before = Files.getLastModifiedTime(committedSource);

        mojo.execute();

        Assert.assertEquals("committed graphml", read(srcResources.resolve(PKG).resolve("DemoProcessor.graphml")));
        Assert.assertEquals(before, Files.getLastModifiedTime(committedSource));
        Assert.assertEquals("regenerated graphml", read(classes.resolve(PKG).resolve("DemoProcessor.graphml")));
        Assert.assertEquals("committed stamp", read(classes.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
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
        write(stagingResources, "DemoProcessor.fluxtion-request.json", "new stamp");
        write(stagingResources, "DemoProcessor.png", "picture");
        Path unchanged = srcResources.resolve(PKG).resolve("DemoProcessor.graphml");
        FileTime before = Files.getLastModifiedTime(unchanged);
        Thread.sleep(20);

        mojo.execute();

        Path pkg = srcJava.resolve(PKG);
        Assert.assertEquals("new source", read(pkg.resolve("DemoProcessor.java")));
        Assert.assertFalse("a file the scan no longer writes is deleted", Files.exists(pkg.resolve("OldProcessor.java")));
        Assert.assertEquals("new stamp", read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
        Assert.assertEquals("unchanged bytes are not rewritten", before, Files.getLastModifiedTime(unchanged));
        Assert.assertFalse("PNG is never promoted to src", Files.exists(srcResources.resolve(PKG).resolve("DemoProcessor.png")));
        Assert.assertEquals("picture", read(classes.resolve(PKG).resolve("DemoProcessor.png")));
        Assert.assertEquals(FluxtionPromoteMojo.files(srcJava.resolve(PKG)).toString(), "[DemoProcessor.java]");
        try (java.util.stream.Stream<Path> left = Files.list(pkg)) {
            Assert.assertEquals("no temporary files left", 1, left.count());
        }
    }

    @Test
    public void aPartlyReusedPackageIsPromotedAndTheReusedProcessorKeepsItsBytes() throws Exception {
        commit();
        write(srcJava, "OtherProcessor.java", "other committed");
        write(stagingJava, "DemoProcessor.java", "committed source");
        write(stagingJava, "OtherProcessor.java", "other regenerated");
        write(stagingResources, "DemoProcessor" + FluxtionPromoteMojo.REUSED_MARKER_SUFFIX, "");
        write(stagingResources, "DemoProcessor.graphml", "committed graphml");
        write(stagingResources, "DemoProcessor.fluxtion-request.json", "committed stamp");

        mojo.execute();

        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals("other regenerated", read(srcJava.resolve(PKG).resolve("OtherProcessor.java")));
        Assert.assertEquals("committed stamp", read(srcResources.resolve(PKG).resolve("DemoProcessor.fluxtion-request.json")));
    }

    @Test
    public void nothingStagedLeavesSrcUnchanged() throws Exception {
        commit();
        mojo.execute();
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        Assert.assertEquals("committed graphml", read(srcResources.resolve(PKG).resolve("DemoProcessor.graphml")));
    }

    @Test
    public void skipAndSkipFluxtionLeaveEverythingAlone() throws Exception {
        commit();
        write(stagingJava, "DemoProcessor.java", "new source");
        mojo.skip = true;
        mojo.execute();
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
        mojo.skip = false;
        System.setProperty("skipFluxtion", "");
        try {
            mojo.execute();
        } finally {
            System.clearProperty("skipFluxtion");
        }
        Assert.assertEquals("committed source", read(srcJava.resolve(PKG).resolve("DemoProcessor.java")));
    }

    @Test(expected = MojoExecutionException.class)
    public void aGeneratedPackageIsRequired() throws Exception {
        mojo.generatedPackage = " ";
        mojo.execute();
    }
}
