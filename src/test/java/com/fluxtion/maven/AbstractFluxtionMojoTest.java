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

    private static class ConcreteFluxtionMojo extends AbstractFluxtionMojo {
        @Override
        public void execute() throws MojoExecutionException {
            // No-op
        }
    }
}
