package com.fluxtion.maven;

import java.io.IOException;
import java.net.URI;

/** Exact provider-origin validation, separate from compiler/admin routing. No trust inferred from a reply. */
final class VerificationOrigin {
    private VerificationOrigin() { }
    static void check(String value, String trustedOrigin) throws IOException {
        if (value == null || value.length() > 2048 || trustedOrigin == null || trustedOrigin.trim().isEmpty())
            throw new IOException("Configure the trusted verification origin before sign-in.");
        try {
            URI uri = URI.create(value);
            URI trusted = URI.create(trustedOrigin);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getHost() == null || !"https".equals(trusted.getScheme()) || trusted.getHost() == null
                    || trusted.getRawUserInfo() != null || trusted.getQuery() != null || trusted.getFragment() != null
                    || (trusted.getPath() != null && !trusted.getPath().isEmpty() && !"/".equals(trusted.getPath()))
                    || !uri.getHost().equalsIgnoreCase(trusted.getHost()) || port(uri) != port(trusted))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IOException("Verification address is not on the configured trusted origin."); }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
