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

public class FluxtionYamlToGenMojoTest {

    private MavenProject project;
    private FluxtionYamlToGenMojo mojo;
    private File baseDir;

    @Before
    public void setUp() throws IOException, org.apache.maven.artifact.DependencyResolutionRequiredException {
        Fluxtion.reset();
        project = mock(MavenProject.class);
        mojo = new FluxtionYamlToGenMojo();
        mojo.project = project;

        baseDir = new File("target/test-mojo-yaml").getAbsoluteFile();
        baseDir.mkdirs();

        when(project.getBasedir()).thenReturn(baseDir);
        Build build = mock(Build.class);
        when(project.getBuild()).thenReturn(build);
        when(build.getSourceDirectory()).thenReturn(new File(baseDir, "src/main/java").getAbsolutePath());
        when(project.getCompileClasspathElements()).thenReturn(Collections.emptyList());
        
        File yamlFile = new File(baseDir, "config.yaml");
        yamlFile.createNewFile();
        mojo.fluxtionConfigFiles = new File[]{yamlFile};
    }

    @Test
    public void testExecuteCallsFluxtionYaml() throws MojoExecutionException {
        mojo.execute();
        Assert.assertTrue("Fluxtion.compileFromReader should have been called", Fluxtion.called);
        Assert.assertNotNull(Fluxtion.passedReader);
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
        Assert.assertFalse("the builder must not run", Fluxtion.called);
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
