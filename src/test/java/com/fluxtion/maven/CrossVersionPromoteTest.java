package com.fluxtion.maven;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Review finding 2: a team on mixed plugin versions alternates the released 1.3.3 promote and this version's on one
 * checkout. Runs the REAL 1.3.3 jar (copied into target/compat by the build) in its own class loader.
 */
public class CrossVersionPromoteTest {

    private static final String PKG = "com/acme/gen";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Child-first for the plugin's own classes, so 1.3.3's FluxtionPromoteMojo is the one loaded. */
    static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL jar, ClassLoader parent) {
            super(new URL[]{jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.fluxtion.maven.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    private Object mojo(ClassLoader loader, Path root) throws Exception {
        Class<?> type = loader.loadClass("com.fluxtion.maven.FluxtionPromoteMojo");
        Object m = type.getDeclaredConstructor().newInstance();
        set(type, m, "generatedPackage", "com.acme.gen");
        set(type, m, "stagingSourceDirectory", root.resolve("target/fluxtion-staging/java").toString());
        set(type, m, "stagingResourcesDirectory", root.resolve("target/fluxtion-staging/resources").toString());
        set(type, m, "sourceDirectory", root.resolve("src/main/java").toString());
        set(type, m, "resourcesDirectory", root.resolve("src/main/resources").toString());
        set(type, m, "classesDirectory", root.resolve("target/classes").toString());
        return m;
    }

    private static void set(Class<?> type, Object target, String field, Object value) throws Exception {
        Field f = type.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void run(Object mojo) throws Throwable {
        try {
            mojo.getClass().getMethod("execute").invoke(mojo);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** A staged generation; with {@code reach}, the 1.0.90 stamp key and the metadata copy as builder 1.0.90 stages them. */
    private static void stage(Path root, String body, boolean reach) throws Exception {
        Path javaDir = root.resolve("target/fluxtion-staging/java/" + PKG);
        Path res = root.resolve("target/fluxtion-staging/resources/" + PKG);
        if (Files.exists(root.resolve("target/fluxtion-staging"))) {
            try (java.util.stream.Stream<Path> walk = Files.walk(root.resolve("target/fluxtion-staging"))) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(javaDir);
        Files.createDirectories(res);
        String source = "package com.acme.gen; public class P {" + body + "}\n";
        Files.write(javaDir.resolve("P.java"), source.getBytes(StandardCharsets.UTF_8));
        Files.write(res.resolve("P.java"), source.getBytes(StandardCharsets.UTF_8));
        String d = String.join("", Collections.nCopies(64, "a"));
        String stamp = "{\n  \"format\": \"fluxtion-request-digest/2\",\n  \"processor\": \"com.acme.gen.P\",\n"
                + (reach ? "  \"reachabilityMetadataDigest\": \"" + String.join("", Collections.nCopies(64, "b")) + "\",\n" : "")
                + "  \"relocatableBase\": null,\n  \"requestDigest\": \"" + d + "\",\n  \"signed\": false,\n  \"sourceDigest\": \""
                + d + "\"\n}\n";
        Files.write(res.resolve("P.fluxtion-request.json"), stamp.getBytes(StandardCharsets.UTF_8));
        if (reach) Files.write(res.resolve("P.reachability-metadata.json"), "{\"reflection\":[]}\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void releasedAndCurrentPromoteAlternateOnOneCheckout() throws Throwable {
        File jar = new File("target/compat/fluxtion-maven-plugin-1.3.3.jar");
        Assume.assumeTrue("the build copies the 1.3.3 jar at generate-test-resources", jar.isFile());
        Path root = tmp.getRoot().toPath();
        ClassLoader released = new ChildFirst(jar.toURI().toURL(), getClass().getClassLoader());
        ClassLoader current = getClass().getClassLoader();
        Path src = root.resolve("src/main/java/" + PKG + "/P.java");

        stage(root, "int v1;", true);
        run(mojo(current, root));
        stage(root, "int v2;", false);          // a teammate on 1.3.3 (builder 1.0.88: no metadata copy)
        run(mojo(released, root));
        Assert.assertTrue(new String(Files.readAllBytes(src), StandardCharsets.UTF_8).contains("v2"));
        stage(root, "int v3;", true);           // back on this version
        run(mojo(current, root));
        Assert.assertTrue(new String(Files.readAllBytes(src), StandardCharsets.UTF_8).contains("v3"));
        stage(root, "int v4;", true);           // and 1.3.3 again, over a stamp carrying the new key
        run(mojo(released, root));
        Assert.assertTrue(new String(Files.readAllBytes(src), StandardCharsets.UTF_8).contains("v4"));
        stage(root, "int v5;", false);
        run(mojo(current, root));
        Assert.assertTrue(new String(Files.readAllBytes(src), StandardCharsets.UTF_8).contains("v5"));
        Assert.assertFalse("this version removes a metadata copy the generation no longer stages",
                Files.exists(root.resolve("src/main/resources/" + PKG + "/P.reachability-metadata.json")));
    }
}
