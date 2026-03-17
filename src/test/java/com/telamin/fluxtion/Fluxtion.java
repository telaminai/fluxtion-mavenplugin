package com.telamin.fluxtion;

import java.io.File;

public class Fluxtion {
    public static boolean called = false;
    public static ClassLoader passedClassLoader;
    public static File[] passedFiles;
    public static java.io.Reader passedReader;

    public static void scanAndGenerateFluxtionBuilders(ClassLoader cl, File[] files) {
        called = true;
        passedClassLoader = cl;
        passedFiles = files;
    }

    public static void compileFromReader(java.io.Reader reader) {
        called = true;
        passedReader = reader;
    }

    public static void reset() {
        called = false;
        passedClassLoader = null;
        passedFiles = null;
        passedReader = null;
    }
}
