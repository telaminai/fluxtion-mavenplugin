package com.fluxtion.maven;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

/** The committed processor's staleness check, which decides whether the first compile leaves it out. */
public class FluxtionPrepareMojoTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FluxtionPrepareMojo mojo;
    private Path src;
    private Path classes;
    private final Properties properties = new Properties();
    private MavenProject project;
    private final java.util.List<String> classpath = new java.util.ArrayList<>();

    @Before
    public void setUp() throws Exception {
        Path root = tmp.getRoot().toPath();
        src = Files.createDirectories(root.resolve("src/main/java"));
        classes = Files.createDirectories(root.resolve("target/classes"));
        project = Mockito.mock(MavenProject.class);
        Build build = new Build();
        build.setOutputDirectory(classes.toString());
        build.setDirectory(root.resolve("target").toString());
        Mockito.when(project.getBuild()).thenReturn(build);
        Mockito.when(project.getProperties()).thenReturn(properties);
        Mockito.when(project.getCompileSourceRoots()).thenReturn(Collections.singletonList(src.toString()));
        // target/classes is on the compile classpath, as in a real build: the check must not let it hide a removal
        classpath.add(classes.toString());
        Mockito.when(project.getCompileClasspathElements()).thenReturn(classpath);
        mojo = new FluxtionPrepareMojo();
        mojo.project = project;
        mojo.generatedPackage = "com.acme.generated";
        mojo.sourceDirectory = src.toString();
        mojo.resourcesDirectory = root.resolve("src/main/resources").toString();
        mojo.excludeProperty = "fluxtion.generated.exclude";
        mojo.staleCheck = true;
    }

    private void source(String relative, String text) throws Exception {
        Path file = src.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aProcessorThatCompilesIsNotExcluded() throws Exception {
        source("com/acme/node/Node.java", "package com.acme.node; public class Node { public void on() {} }");
        source("com/acme/generated/P.java", "package com.acme.generated; import com.acme.node.Node;"
                + " public class P { Node node = new Node(); void run() { node.on(); } }");
        mojo.execute();
        Assert.assertEquals(FluxtionPrepareMojo.EXCLUDES_NOTHING, properties.getProperty("fluxtion.generated.exclude"));
        Assert.assertFalse("the check writes nothing into target/classes", new File(classes.toFile(), "com").exists());
    }

    @Test
    public void aProcessorReferencingARemovedNodeIsExcludedEvenWithItsOldClassInTargetClasses() throws Exception {
        source("com/acme/generated/P.java", "package com.acme.generated; import com.acme.node.Removed;"
                + " public class P { Removed node = new Removed(); }");
        Path stale = classes.resolve("com/acme/node/Removed.class");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        mojo.execute();
        Assert.assertEquals("com/acme/generated/**", properties.getProperty("fluxtion.generated.exclude"));
    }

    @Test
    public void aChangedNodeMethodIsStaleToo() throws Exception {
        source("com/acme/node/Node.java", "package com.acme.node; public class Node { public void renamed() {} }");
        source("com/acme/generated/P.java", "package com.acme.generated; import com.acme.node.Node;"
                + " public class P { Node node = new Node(); void run() { node.on(); } }");
        Assert.assertNotNull(mojo.staleProblem(src.resolve("com/acme/generated")));
    }

    @Test
    public void aProfileSetExclusionIsKept() throws Exception {
        properties.setProperty("fluxtion.generated.exclude", "com/acme/generated/**");
        source("com/acme/generated/P.java", "package com.acme.generated; public class P {}");
        mojo.execute();
        Assert.assertEquals("com/acme/generated/**", properties.getProperty("fluxtion.generated.exclude"));
    }

    @Test
    public void noCommittedProcessorExcludesNothing() throws Exception {
        mojo.execute();
        Assert.assertEquals(FluxtionPrepareMojo.EXCLUDES_NOTHING, properties.getProperty("fluxtion.generated.exclude"));
    }

    // ---- review finding 5 -------------------------------------------------------------------------------

    private static String lombokJar() {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (element.contains("lombok")) return element;
        }
        throw new IllegalStateException("lombok is a test dependency");
    }

    /** The reviewer's case: a node whose setter Lombok generates. Processors on the classpath run, so it compiles. */
    @Test
    public void aClasspathAnnotationProcessorIsHonoured() throws Exception {
        classpath.add(lombokJar());
        source("com/acme/node/Node.java", "package com.acme.node; @lombok.Setter @lombok.Getter public class Node { private int limit; }");
        source("com/acme/generated/P.java", "package com.acme.generated; import com.acme.node.Node;"
                + " public class P { final Node node = new Node(); { node.setLimit(3); } }");
        Assert.assertNull(mojo.staleProblem(src.resolve("com/acme/generated")));
        source("com/acme/node/Node.java", "package com.acme.node; @lombok.Getter public class Node { private int limit; }");
        Assert.assertNotNull("without the generated setter it is stale", mojo.staleProblem(src.resolve("com/acme/generated")));
    }

    /** annotationProcessorPaths cannot be reproduced here, so the check is skipped rather than guessed. */
    @Test
    public void configuredProcessorPathsSkipTheCheck() throws Exception {
        org.apache.maven.model.Plugin compiler = new org.apache.maven.model.Plugin();
        compiler.setArtifactId("maven-compiler-plugin");
        org.codehaus.plexus.util.xml.Xpp3Dom conf = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        conf.addChild(new org.codehaus.plexus.util.xml.Xpp3Dom("annotationProcessorPaths"));
        compiler.setConfiguration(conf);
        Mockito.when(project.getBuildPlugins()).thenReturn(Collections.singletonList(compiler));
        source("com/acme/generated/P.java", "package com.acme.generated; import com.acme.node.Removed; public class P { Removed r; }");
        Assert.assertNull(mojo.staleProblem(src.resolve("com/acme/generated")));
    }

    /** The compiler plugin's own release is used (here 8: String.isBlank() does not exist there). */
    @Test
    public void theCompilerPluginsReleaseIsHonoured() throws Exception {
        source("com/acme/generated/P.java", "package com.acme.generated; public class P { boolean b = \"\".isBlank(); }");
        Assert.assertNull(mojo.staleProblem(src.resolve("com/acme/generated")));
        org.apache.maven.model.Plugin compiler = new org.apache.maven.model.Plugin();
        compiler.setArtifactId("maven-compiler-plugin");
        org.codehaus.plexus.util.xml.Xpp3Dom conf = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom release = new org.codehaus.plexus.util.xml.Xpp3Dom("release");
        release.setValue("${my.release}");
        conf.addChild(release);
        compiler.setConfiguration(conf);
        properties.setProperty("my.release", "8");
        Mockito.when(project.getBuildPlugins()).thenReturn(Collections.singletonList(compiler));
        Assert.assertNotNull(mojo.staleProblem(src.resolve("com/acme/generated")));
    }
}
