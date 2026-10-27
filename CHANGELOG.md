# Changelog

## [1.3.1]

Requires `com.telamin.fluxtion:fluxtion-builder` **1.0.82 or later** on the project's build classpath for compiler
accounts (token mode). An older builder ignores the credential and keeps using the RapidAPI key; with no credential,
every goal behaves exactly as in 1.3.0.

### Added
- `mvn fluxtion:login`: device sign-in to a Fluxtion compiler account. Prints a short code and a verification
  address, offers (never forces) the browser, and stores the credential owner-only in `~/.fluxtion/credentials`
  (an owner-only ACL on Windows). The credential is named `Maven on <host>` in the account's credential list.
  Refuses to run on CI.
- After sign-in, `fluxtion:login` prints the account, the plan, the compile and interpret credits left, and the
  plan's limits, for example `Plan limits: 20 nodes, 6 inputs (event types + exported methods), 1 exported
  interface, targets Java.` A compiler from before plans reports only compile credits; one from before the inputs
  limit reports its event-type limit as `N event types`.
- `mvn fluxtion:logout`: revokes the stored credential at the compiler that issued it and deletes the file. A 401
  from the service is reported as unconfirmed, never as revoked.
- `scan`, `springToFluxtion` and `yamlToFluxtion` use `FLUXTION_API_TOKEN` (CI) or the stored login, sending the
  credential only to the compiler that issued it (`FLUXTION_COMPILER_URL` / `<compilerUrl>`), and only for the
  duration of the builder call. Parallel builds (`-T`) take turns.

### Changed
- A build the compiler refuses now fails with the compiler's own reason as the message, for example
  `The Fluxtion compiler refused this build. Your plan does not allow this graph: ... (maxNodes: 120 > 20 (free));
  a higher plan allows more [HTTP 403, request ...]`. The limit named may also be `maxInputs` or
  `maxExportedInterfaces`. Nothing is charged for a refused build.

### Security
- A credential is never accepted from a POM, a parent POM, a profile, `.mvn/maven.config` or `.mvn/jvm.config`;
  the build fails and points to the environment variable or `fluxtion:login`.
- `FLUXTION_API_TOKEN` without a compiler URL fails rather than reach the builder's default host.

## [1.3.0] - 2026-03-17
- Group and artifact ids moved to `com.telamin.fluxtion:fluxtion-maven-plugin`.
