package com.telamin.fluxtion.builder.extern.spring;

import java.io.File;

public class FluxtionSpring {
    public static boolean called = false;
    public static ClassLoader passedClassLoader;
    public static File passedSpringFile;
    public static String passedClassName;
    public static String passedPackageName;

    public static void compileAot(ClassLoader cl, File springFile, String className, String packageName) {
        called = true;
        passedClassLoader = cl;
        passedSpringFile = springFile;
        passedClassName = className;
        passedPackageName = packageName;
    }

    public static void reset() {
        called = false;
        passedClassLoader = null;
        passedSpringFile = null;
        passedClassName = null;
        passedPackageName = null;
    }
}
