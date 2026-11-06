# Changelog

## [Unreleased]

- Refuse promotion over existing files without a valid committed processor stamp or unchanged prior promotion marker; staging alone does not establish ownership.
- Record promotion ownership separately from reuse stamps, so custom-template output can be promoted repeatedly while subsequent user edits and interrupted writes fail safely.
- Preflight classes resource destinations for linked directories/files on generation and reuse before any writes.

### Added
- `scan` ensure mode: `ensureProcessor` (`-Dfluxtion.ensureProcessor`), `ensureSourceDirectory`,
  `ensureResourcesDirectory` and `relocatableBase`. The build reuses the committed processor when the compile request
  is unchanged and regenerates, or fails with `FLX-1022` when not signed in, otherwise. `-Dfluxtion.regenerate=true`
  (or a profile's `fluxtion.regenerate` property) forces generation. Requires `fluxtion-builder` 1.0.87 or later.
- `promote` goal: copies a staged scan's resources to `target/classes` and, unless the builder reused every
  processor, replaces the generator's files in the generated package in `src/` (temporary file then rename, with
  permissions kept or defaulted; unchanged files left alone; a renamed processor's stamped files deleted; PNG never
  promoted). Only `<Processor>` files of staged or stamped processors in the package's own directory are touched.
  Staging with no processor, or a symbolic link to the package or to a processor's file, fails the build. Replaces the clean and copy-resources executions a staged build needed;
  the staging clear at `initialize` stays.
- `scan` with `ensureProcessor` fails at once when the project's builder has no ensure support.

### Changed
- A build the builder refuses with a coded diagnostic (for example `FLX-1022`) fails with the diagnostic's line as
  the message, rather than "problem setting building fluxtion class loader".
- A coded diagnostic's stack trace is logged at debug, since the builder has already printed the diagnostic.
- `scan`, `springToFluxtion` and `yamlToFluxtion` take turns under `-T`, so parallel modules never see each other's
  builder properties.
- `scan` restores `FLUXTION.OUTPUT.DIRECTORY` and `FLUXTION.RESOURCES.DIRECTORY` when it finishes, as it does the
  credential properties.

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
