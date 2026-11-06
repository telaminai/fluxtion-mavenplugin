package com.fluxtion.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.Collections;

public class AbstractFluxtionMojoTest {

    private MavenProject project;
    private ConcreteFluxtionMojo mojo;

    @Before
    public void setUp() {
        project = Mockito.mock(MavenProject.class);
        mojo = new ConcreteFluxtionMojo();
        mojo.project = project;
    }

    @Test
    public void testBuildFluxtionClassLoader() throws MalformedURLException, DependencyResolutionRequiredException {
        File tempFile = new File("target/test-classes");
        tempFile.mkdirs();
        String absolutePath = tempFile.getAbsolutePath();
        Mockito.when(project.getCompileClasspathElements()).thenReturn(Collections.singletonList(absolutePath));

        URLClassLoader classLoader = mojo.buildFluxtionClassLoader();
        Assert.assertNotNull(classLoader);
        
        String classpathProperty = System.getProperty("FLUXTION.GENERATION.CLASSPATH");
        Assert.assertEquals(absolutePath, classpathProperty);
    }

    /** What ManagedAuthTransport.explain (fluxtion-compiler PR #121) throws for a 403 CAPABILITY_EXCEEDED. */
    private static final String CAPABILITY_REFUSAL = "Your plan does not allow this graph: your plan does not allow"
            + " this request (maxNodes: 120 > 20 (free)); a higher plan allows more [HTTP 403, request fx-1]";

    @Test
    public void aCompilerRefusalIsTheBuildFailureMessage() {
        // as it arrives: the generator method invoked reflectively, the builder wrapping the client's IOException
        Exception failure = new java.lang.reflect.InvocationTargetException(
                new RuntimeException(new java.io.IOException(CAPABILITY_REFUSAL)));
        MojoExecutionException e = AbstractFluxtionMojo.generationFailure("problem setting building fluxtion class loader",
                failure);
        Assert.assertEquals("The Fluxtion compiler refused this build. " + CAPABILITY_REFUSAL, e.getMessage());
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("maxNodes: 120 > 20 (free)"));
        Assert.assertSame(failure, e.getCause());
    }

    @Test
    public void otherFailuresKeepTheirMessage() {
        Exception failure = new java.lang.reflect.InvocationTargetException(new IllegalStateException("no builders"));
        Assert.assertEquals("problem setting building fluxtion class loader",
                AbstractFluxtionMojo.generationFailure("problem setting building fluxtion class loader", failure)
                        .getMessage());
        Assert.assertNull(AbstractFluxtionMojo.compilerRefusal(null));
    }

    /** Shaped like the builder's FLX-1022 ensure-mode refusal as the scan entry point wraps it (DiagnosticBuildException). */
    static final String STALE_PROCESSOR = "FLX-1022  ERROR  Committed processor 'com.acme.DemoProcessor' does not"
            + " match the current compile request (request-changed)  (details above; sidecar written to target/x.json)";

    /** A fabricated diagnostic carrier: the builder's exceptions are not on the plugin's classpath, only their shape. */
    public static class FabricatedDiagnosticException extends RuntimeException {
        private final String code;

        public FabricatedDiagnosticException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /** As it arrives from the scan: reflective invocation, the wrapping carrier, the legacy-prose carrier below it. */
    static Exception staleProcessorFailure() {
        return new java.lang.reflect.InvocationTargetException(new FabricatedDiagnosticException("FLX-1022",
                STALE_PROCESSOR, new FabricatedDiagnosticException("FLX-1022",
                "committed processor is stale and this build is not signed in", null)));
    }

    @Test
    public void aCodedBuilderDiagnosticIsTheBuildFailureMessage() {
        Exception failure = staleProcessorFailure();
        MojoExecutionException e = AbstractFluxtionMojo.generationFailure("problem setting building fluxtion class loader",
                failure);
        Assert.assertEquals(STALE_PROCESSOR, e.getMessage());
        Assert.assertSame(failure, e.getCause());
    }

    @Test
    public void aCarrierMessageWithoutItsCodeIsPrefixedWithIt() {
        Exception failure = new java.lang.reflect.InvocationTargetException(
                new FabricatedDiagnosticException("FLX-1022", "committed processor is stale", null));
        Assert.assertEquals("FLX-1022  committed processor is stale",
                AbstractFluxtionMojo.generationFailure("fallback", failure).getMessage());
    }

    @Test
    public void aGetCodeThatIsNotADiagnosticCodeIsIgnored() {
        Exception failure = new java.lang.reflect.InvocationTargetException(
                new FabricatedDiagnosticException("E42", "something else", null));
        Assert.assertEquals("fallback", AbstractFluxtionMojo.generationFailure("fallback", failure).getMessage());
        Assert.assertNull(AbstractFluxtionMojo.builderDiagnostic(null));
    }

    @Test
    public void aCompilerRefusalStillWinsOverACodedWrapper() {
        Exception failure = new java.lang.reflect.InvocationTargetException(new FabricatedDiagnosticException(
                "FLX-1100", "FLX-1100  ERROR  remote refusal", new java.io.IOException(CAPABILITY_REFUSAL)));
        Assert.assertEquals("The Fluxtion compiler refused this build. " + CAPABILITY_REFUSAL,
                AbstractFluxtionMojo.generationFailure("fallback", failure).getMessage());
    }

    @Test
    public void aCodeMatchesOnlyAsAWholeWord() {
        Assert.assertTrue(AbstractFluxtionMojo.namesCode("FLX-1021  ERROR  stale", "FLX-1021"));
        Assert.assertTrue(AbstractFluxtionMojo.namesCode("refused (FLX-102): x", "FLX-102"));
        Assert.assertFalse(AbstractFluxtionMojo.namesCode("FLX-1021  ERROR  stale", "FLX-102"));
        Assert.assertFalse(AbstractFluxtionMojo.namesCode("see FLX-102-b", "FLX-102"));
        Exception failure = new java.lang.reflect.InvocationTargetException(
                new FabricatedDiagnosticException("FLX-102", "unlike FLX-1021, this one", null));
        Assert.assertEquals("FLX-102  unlike FLX-1021, this one",
                AbstractFluxtionMojo.generationFailure("fallback", failure).getMessage());
    }

    @Test
    public void aCodedFailureLogsItsStackAtDebugAndAnyOtherAtError() {
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);
        mojo.logGenerationFailure(staleProcessorFailure());
        Assert.assertTrue(log.all(), log.all().startsWith("DEBUG "));
        log.lines.clear();
        mojo.logGenerationFailure(new java.lang.reflect.InvocationTargetException(new IllegalStateException("x")));
        Assert.assertTrue(log.all(), log.all().startsWith("ERROR "));
        log.lines.clear();
        mojo.logGenerationFailure(new java.lang.reflect.InvocationTargetException(
                new RuntimeException(new java.io.IOException(CAPABILITY_REFUSAL))));
        Assert.assertTrue(log.all(), log.all().startsWith("ERROR "));
    }

    private static class ConcreteFluxtionMojo extends AbstractFluxtionMojo {
        @Override
        public void execute() throws MojoExecutionException {
            // No-op
        }
    }
}
