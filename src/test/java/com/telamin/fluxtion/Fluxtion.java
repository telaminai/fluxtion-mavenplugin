package com.telamin.fluxtion;

import java.io.File;

public class Fluxtion {
    public static boolean called = false;
    public static ClassLoader passedClassLoader;
    public static File[] passedFiles;
    public static java.io.Reader passedReader;
    /** The builder's auth-related system properties as they were while the builder ran. */
    public static String seenAuth;
    public static String seenToken;
    public static String seenHost;
    public static String seenPort;
    public static String seenIssuer;
    /** The builder's directory and ensure-mode system properties as they were while the builder ran. */
    public static final java.util.Map<String, String> seenProperties = new java.util.HashMap<>();
    /** Thrown from the scan when set, as the builder would throw a refusal. */
    public static RuntimeException failWith;
    private static final String[] RECORDED = {"FLUXTION.OUTPUT.DIRECTORY", "FLUXTION.RESOURCES.DIRECTORY",
            "FLUXTION.ENSURE.SOURCE.DIRECTORY", "FLUXTION.ENSURE.RESOURCES.DIRECTORY",
            "FLUXTION.ENSURE.RELOCATABLE.BASE", "fluxtion.regenerate"};

    public static void scanAndGenerateFluxtionBuilders(ClassLoader cl, File[] files) {
        called = true;
        passedClassLoader = cl;
        passedFiles = files;
        recordAuth();
        for (String key : RECORDED) {
            seenProperties.put(key, System.getProperty(key));
        }
        if (failWith != null) {
            throw failWith;
        }
    }

    public static void compileFromReader(java.io.Reader reader) {
        called = true;
        passedReader = reader;
        recordAuth();
    }

    private static void recordAuth() {
        seenAuth = System.getProperty("fluxtion.remote.auth");
        seenToken = System.getProperty("fluxtion.remote.token");
        seenHost = System.getProperty("fluxtion.remote.host");
        seenPort = System.getProperty("fluxtion.remote.port");
        seenIssuer = System.getProperty("fluxtion.remote.tokenIssuer");
    }

    public static void reset() {
        called = false;
        passedClassLoader = null;
        passedFiles = null;
        passedReader = null;
        seenAuth = null;
        seenToken = null;
        seenHost = null;
        seenPort = null;
        seenIssuer = null;
        seenProperties.clear();
        failWith = null;
    }
}
