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

    /** Review M3: a builder without token mode (older than 1.0.82) would use the retired key route: refused. */
    @Test
    public void aBuilderWithoutTokenModeIsRefusedBeforeGeneration() {
        mojo.tokenModeSupportClass = "com.telamin.fluxtion.builder.NotInThisBuilder";
        try {
            mojo.execute();
            Assert.fail("refused");
        } catch (MojoExecutionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("older than 1.0.82"));
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("retired RapidAPI key route"));
        }
        Assert.assertFalse("the builder must not run", FluxtionSpring.called);
    }

    /** Review L9: the 1.0.90 upgrade warning is said once per build, by this goal too. */
    @Test
    public void theUpgradeWarningIsSaidOncePerBuild() throws MojoExecutionException {
        AbstractFluxtionMojo.resetUpgradeWarningForTest();
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);
        mojo.execute();
        mojo.execute();
        String all = log.all();
        Assert.assertEquals(all, all.indexOf("older than 1.0.90"), all.lastIndexOf("older than 1.0.90"));
        Assert.assertTrue(all, all.contains("older than 1.0.90"));
    }
}
