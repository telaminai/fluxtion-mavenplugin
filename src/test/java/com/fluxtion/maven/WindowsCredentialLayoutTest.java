package com.fluxtion.maven;

import com.sun.jna.Native;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Portable ABI checks, not a substitute for exercising native operations on Windows. */
public class WindowsCredentialLayoutTest {
    public static class Attributes extends WindowsCredentialFile.ObjectAttributes {
        int offset(String name) { return fieldOffset(name); }
    }
    public static class Unicode extends WindowsCredentialFile.UnicodeString {
        int offset(String name) { return fieldOffset(name); }
    }
    @Test public void nativeStructuresUseWindowsPointerAlignment() {
        boolean wide = Native.POINTER_SIZE == 8;
        Attributes attributes = new Attributes();
        assertEquals(wide ? 48 : 24, attributes.size());
        assertEquals(wide ? 8 : 4, attributes.offset("rootDirectory"));
        assertEquals(wide ? 16 : 8, attributes.offset("objectName"));
        assertEquals(wide ? 32 : 16, attributes.offset("securityDescriptor"));
        Unicode unicode = new Unicode();
        assertEquals(wide ? 16 : 8, unicode.size());
        assertEquals(wide ? 8 : 4, unicode.offset("buffer"));
        assertEquals(Native.POINTER_SIZE * 2, new WindowsCredentialFile.IoStatusBlock().size());
    }
}
