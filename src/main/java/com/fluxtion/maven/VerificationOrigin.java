package com.fluxtion.maven;

import java.io.IOException;
import java.net.URI;

/** Exact provider-origin validation, separate from compiler/admin routing. No trust inferred from a reply. */
final class VerificationOrigin {
    private VerificationOrigin() { }
    static String trusted(String compilerOrigin, String explicit) {
        if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();
        if ("https://compiler-staging.fluxtion-playground.dev".equals(normalizeCompilerOrigin(compilerOrigin)))
            return "https://meticulous-comfort-69-staging.authkit.app";
        // Production deliberately unconfigured until the operator supplies its exact WorkOS origin.
        return null;
    }
    private static String normalizeCompilerOrigin(String value) {
        try {
            URI uri=URI.create(value);
            if(uri.getHost()==null || uri.getScheme()==null || uri.getRawUserInfo()!=null
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || (uri.getRawPath()!=null&&!uri.getRawPath().isEmpty()&&!"/".equals(uri.getRawPath()))) return null;
            String scheme=uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port=uri.getPort();
            boolean standard=port==-1 || ("https".equals(scheme)&&port==443) || ("http".equals(scheme)&&port==80);
            return scheme+"://"+host+(standard?"":":"+port);
        } catch(IllegalArgumentException|NullPointerException e){return null;}
    }
    static void check(String value, String trustedOrigin) throws IOException {
        if (value == null || value.length() > 2048 || trustedOrigin == null || trustedOrigin.trim().isEmpty())
            throw new IOException("Configure the trusted verification origin before sign-in.");
        try {
            URI uri = URI.create(value);
            URI trusted = URI.create(trustedOrigin);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getHost() == null || !"https".equalsIgnoreCase(trusted.getScheme()) || trusted.getHost() == null
                    || trusted.getRawUserInfo() != null || trusted.getQuery() != null || trusted.getFragment() != null
                    || (trusted.getPath() != null && !trusted.getPath().isEmpty() && !"/".equals(trusted.getPath()))
                    || !uri.getHost().equalsIgnoreCase(trusted.getHost()) || port(uri) != port(trusted))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IOException("Verification address is not on the configured trusted origin."); }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
}
