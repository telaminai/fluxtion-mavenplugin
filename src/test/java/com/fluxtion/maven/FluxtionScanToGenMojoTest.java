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
}
