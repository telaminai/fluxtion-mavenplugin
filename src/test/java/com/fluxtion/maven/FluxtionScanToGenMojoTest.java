package com.fluxtion.maven;

import com.telamin.fluxtion.Fluxtion;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FluxtionScanToGenMojoTest {

    private MavenProject project;
    private FluxtionScanToGenMojo mojo;
    private File baseDir;

    @Before
    public void setUp() throws IOException, org.apache.maven.artifact.DependencyResolutionRequiredException {
        Fluxtion.reset();
        project = mock(MavenProject.class);
        mojo = new FluxtionScanToGenMojo();
        mojo.project = project;

        baseDir = new File("target/test-mojo").getAbsoluteFile();
        baseDir.mkdirs();

        when(project.getBasedir()).thenReturn(baseDir);
        Build build = mock(Build.class);
        when(project.getBuild()).thenReturn(build);
        when(build.getSourceDirectory()).thenReturn(new File(baseDir, "src/main/java").getAbsolutePath());
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
    }

    @Test
    public void testExecuteCallsFluxtion() throws MojoExecutionException {
        mojo.execute();
        Assert.assertTrue("Fluxtion.scanAndGenerateFluxtionBuilders should have been called", Fluxtion.called);
        Assert.assertNotNull(Fluxtion.passedClassLoader);
        Assert.assertNotNull(Fluxtion.passedFiles);
        Assert.assertEquals(1, Fluxtion.passedFiles.length);
        Assert.assertTrue(Fluxtion.passedFiles[0].getAbsolutePath().endsWith("target/classes"));
    }

    @Test
    public void testSkipFluxtion() throws MojoExecutionException {
        System.setProperty("skipFluxtion", "true");
        try {
            mojo.execute();
            Assert.assertFalse("Fluxtion.scanAndGenerateFluxtionBuilders should not have been called", Fluxtion.called);
        } finally {
            System.clearProperty("skipFluxtion");
        }
    }

    private static final String[] PROPERTIES = {FluxtionScanToGenMojo.OUTPUT_DIRECTORY,
            FluxtionScanToGenMojo.RESOURCES_DIRECTORY, FluxtionScanToGenMojo.ENSURE_SOURCE_DIRECTORY,
            FluxtionScanToGenMojo.ENSURE_RESOURCES_DIRECTORY, FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE,
            FluxtionScanToGenMojo.REGENERATE};

    private static Map<String, String> snapshot() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : PROPERTIES) values.put(key, System.getProperty(key));
        return values;
    }

    private static void restore(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) System.clearProperty(e.getKey());
            else System.setProperty(e.getKey(), e.getValue());
        }
    }

    @Test
    public void ensureOffSetsNoEnsureProperties() throws MojoExecutionException {
        Map<String, String> before = snapshot();
        try {
            System.clearProperty(FluxtionScanToGenMojo.REGENERATE);
            mojo.relocatableBase = "com.acme";
            mojo.regenerate = "true";
            mojo.execute();
            Assert.assertTrue(Fluxtion.called);
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_SOURCE_DIRECTORY));
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RESOURCES_DIRECTORY));
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE));
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.REGENERATE));
        } finally {
            restore(before);
        }
    }

    @Test
    public void ensureDefaults() throws MojoExecutionException, IOException {
        Map<String, String> before = snapshot();
        try {
            System.clearProperty(FluxtionScanToGenMojo.REGENERATE);
            mojo.ensureProcessor = true;
            mojo.execute();
            Assert.assertEquals(new File(baseDir, "src/main/java").getAbsolutePath(),
                    Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_SOURCE_DIRECTORY));
            Assert.assertEquals(baseDir.getCanonicalPath() + "/src/main/resources",
                    Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RESOURCES_DIRECTORY));
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE));
            Assert.assertNull(Fluxtion.seenProperties.get(FluxtionScanToGenMojo.REGENERATE));
        } finally {
            restore(before);
        }
    }

    @Test
    public void ensurePropertiesAreSetForTheScanAndRestoredAfter() throws MojoExecutionException, IOException {
        Map<String, String> before = snapshot();
        try {
            System.setProperty(FluxtionScanToGenMojo.OUTPUT_DIRECTORY, "previous-output");
            System.setProperty(FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE, "previous-base");
            System.clearProperty(FluxtionScanToGenMojo.ENSURE_SOURCE_DIRECTORY);
            System.clearProperty(FluxtionScanToGenMojo.ENSURE_RESOURCES_DIRECTORY);
            System.clearProperty(FluxtionScanToGenMojo.RESOURCES_DIRECTORY);
            System.clearProperty(FluxtionScanToGenMojo.REGENERATE);
            Map<String, String> previous = snapshot();
            mojo.ensureProcessor = true;
            mojo.ensureSourceDirectory = "committed/java";
            mojo.ensureResourcesDirectory = "/abs/committed/resources";
            mojo.relocatableBase = " com.acme.demo ";
            mojo.regenerate = "true";
            mojo.execute();
            Assert.assertEquals(new File(baseDir.getCanonicalPath(), "committed/java").getPath(),
                    Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_SOURCE_DIRECTORY));
            Assert.assertEquals("/abs/committed/resources",
                    Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RESOURCES_DIRECTORY));
            Assert.assertEquals("com.acme.demo", Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE));
            Assert.assertEquals("true", Fluxtion.seenProperties.get(FluxtionScanToGenMojo.REGENERATE));
            Assert.assertEquals(new File(baseDir, "src/main/java").getAbsolutePath(),
                    Fluxtion.seenProperties.get(FluxtionScanToGenMojo.OUTPUT_DIRECTORY));
            Assert.assertEquals(previous, snapshot());
        } finally {
            restore(before);
        }
    }

    @Test
    public void aRegenerateSystemPropertyPassesThroughUnchanged() throws MojoExecutionException {
        Map<String, String> before = snapshot();
        try {
            System.setProperty(FluxtionScanToGenMojo.REGENERATE, "true");
            mojo.ensureProcessor = true;
            mojo.execute();
            Assert.assertEquals("true", Fluxtion.seenProperties.get(FluxtionScanToGenMojo.REGENERATE));
            Assert.assertEquals("true", System.getProperty(FluxtionScanToGenMojo.REGENERATE));
        } finally {
            restore(before);
        }
    }

    @Test
    public void skipFluxtionSetsNothingInEnsureMode() throws MojoExecutionException {
        Map<String, String> before = snapshot();
        System.setProperty("skipFluxtion", "true");
        try {
            for (String key : PROPERTIES) System.clearProperty(key);
            mojo.ensureProcessor = true;
            mojo.execute();
            Assert.assertFalse(Fluxtion.called);
            for (String key : PROPERTIES) Assert.assertNull(key, System.getProperty(key));
        } finally {
            System.clearProperty("skipFluxtion");
            restore(before);
        }
    }

    @Test
    public void aStaleProcessorRefusalIsTheFailureAndPropertiesAreRestored() throws Exception {
        Map<String, String> before = snapshot();
        try {
            for (String key : PROPERTIES) System.clearProperty(key);
            Fluxtion.failWith = (RuntimeException) AbstractFluxtionMojoTest.staleProcessorFailure().getCause();
            mojo.ensureProcessor = true;
            mojo.relocatableBase = "com.acme";
            try {
                mojo.execute();
                Assert.fail("expected the refusal");
            } catch (MojoExecutionException e) {
                Assert.assertEquals(AbstractFluxtionMojoTest.STALE_PROCESSOR, e.getMessage());
            }
            Assert.assertFalse("the -T lock is released", CompilerCredentials.builderPropertiesLocked());
            Assert.assertEquals("com.acme", Fluxtion.seenProperties.get(FluxtionScanToGenMojo.ENSURE_RELOCATABLE_BASE));
            for (String key : PROPERTIES) Assert.assertNull(key, System.getProperty(key));
        } finally {
            restore(before);
        }
    }

    @Test
    public void anOldBuilderFailsFastInEnsureMode() throws Exception {
        Map<String, String> before = snapshot();
        try {
            for (String key : PROPERTIES) System.clearProperty(key);
            mojo.ensureProcessor = true;
            mojo.ensureSupportClass = "com.telamin.fluxtion.builder.compile.generation.NotInThisBuilder";
            try {
                mojo.execute();
                Assert.fail("expected an old builder to be refused");
            } catch (MojoExecutionException e) {
                Assert.assertTrue(e.getMessage(), e.getMessage().startsWith("ensureProcessor needs"
                        + " com.telamin.fluxtion:fluxtion-builder 1.0.87 or later"));
            }
            Assert.assertFalse("the scan must not run", Fluxtion.called);
            Assert.assertFalse("the -T lock is released", CompilerCredentials.builderPropertiesLocked());
            for (String key : PROPERTIES) Assert.assertNull(key, System.getProperty(key));
        } finally {
            restore(before);
        }
    }

    @Test
    public void anOldBuilderIsNotProbedWithoutEnsureMode() throws Exception {
        Map<String, String> before = snapshot();
        try {
            mojo.ensureSupportClass = "com.telamin.fluxtion.builder.compile.generation.NotInThisBuilder";
            mojo.execute();
            Assert.assertTrue(Fluxtion.called);
        } finally {
            restore(before);
        }
    }

    /**
     * Post-G3 L1: the ensure-mode scan (where promotion recovery and reachability run) still hands the builder token
     * mode with no credential, so builders 1.0.87-1.0.89, whose default was the key route, never fall back to it.
     */
    @Test
    public void ensureModeWithNoCredentialStillRunsTheBuilderInTokenMode() throws Exception {
        Map<String, String> before = snapshot();
        try {
            System.clearProperty("fluxtion.remote.auth");
            mojo.env = k -> null;
            mojo.ensureProcessor = true;
            mojo.execute();
            Assert.assertEquals("token mode on the ensure path too", "token", Fluxtion.seenAuth);
            Assert.assertNull(Fluxtion.seenToken);
            Assert.assertNull("restored afterwards", System.getProperty("fluxtion.remote.auth"));
        } finally {
            restore(before);
        }
    }

    /** Post-G3 L1: the ensure-mode scan refuses a builder without token mode, and says the 1.0.90 warning. */
    @Test
    public void ensureModeChecksTheBuilderToo() throws Exception {
        Map<String, String> before = snapshot();
        try {
            mojo.env = k -> null;
            mojo.ensureProcessor = true;
            AbstractFluxtionMojo.resetUpgradeWarningForTest();
            RecordingLog log = new RecordingLog();
            mojo.setLog(log);
            mojo.execute();
            Assert.assertTrue(log.all(), log.all().contains("older than 1.0.90"));

            Fluxtion.reset();
            mojo.tokenModeSupportClass = "com.telamin.fluxtion.builder.NotInThisBuilder";
            try {
                mojo.execute();
                Assert.fail("refused");
            } catch (MojoExecutionException e) {
                Assert.assertTrue(e.getMessage(), e.getMessage().contains("older than 1.0.82"));
            }
            Assert.assertFalse("the builder must not run", Fluxtion.called);
        } finally {
            restore(before);
        }
    }
}
