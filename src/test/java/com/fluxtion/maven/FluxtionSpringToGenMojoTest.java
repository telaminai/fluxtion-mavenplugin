package com.fluxtion.maven;

import com.telamin.fluxtion.builder.extern.spring.FluxtionSpring;
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

public class FluxtionSpringToGenMojoTest {

    private MavenProject project;
    private FluxtionSpringToGenMojo mojo;
    private File baseDir;

    @Before
    public void setUp() throws IOException, org.apache.maven.artifact.DependencyResolutionRequiredException {
        FluxtionSpring.reset();
        project = mock(MavenProject.class);
        mojo = new FluxtionSpringToGenMojo();
        mojo.project = project;

        baseDir = new File("target/test-mojo-spring").getAbsoluteFile();
        baseDir.mkdirs();

        when(project.getBasedir()).thenReturn(baseDir);
        Build build = mock(Build.class);
        when(project.getBuild()).thenReturn(build);
        when(build.getSourceDirectory()).thenReturn(new File(baseDir, "src/main/java").getAbsolutePath());
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        
        mojo.className = "MyClass";
        mojo.packageName = "com.example";
        mojo.springFile = new File(baseDir, "spring.xml");
        mojo.springFile.createNewFile();
    }

    @Test
    public void testExecuteCallsFluxtionSpring() throws MojoExecutionException {
        mojo.execute();
        Assert.assertTrue("FluxtionSpring.compileAot should have been called", FluxtionSpring.called);
        Assert.assertNotNull(FluxtionSpring.passedClassLoader);
        Assert.assertEquals(mojo.springFile, FluxtionSpring.passedSpringFile);
        Assert.assertEquals(mojo.className, FluxtionSpring.passedClassName);
        Assert.assertEquals(mojo.packageName, FluxtionSpring.passedPackageName);
    }
}
