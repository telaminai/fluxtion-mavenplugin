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

    private static class ConcreteFluxtionMojo extends AbstractFluxtionMojo {
        @Override
        public void execute() throws MojoExecutionException {
            // No-op
        }
    }
}
