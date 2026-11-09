# Compiler sign-in OS acceptance

`signin_os_acceptance.py` runs the real `mvn` goals `login` (approve, deny, expire, and refused without a trusted
verification origin), `account` and `logout` as separate non-interactive processes against a synthetic device
authorisation and compiler service on `127.0.0.1` (port 0). Each case uses a temporary home passed as `-Duser.home`
(and `HOME`/`USERPROFILE`), so no real sign-in is read or changed. The trusted verification origin is set only for
the test, with the plugin's existing `-Dfluxtion.login.verificationOrigin` user property; the built-in origins are
unchanged.

It checks that the address and code are printed before approval, Maven's exit status, owner-only credential storage
(POSIX `0600`/`0700`; on Windows a protected ACL granting only the current user), that logout sends an authenticated
`DELETE` and removes the file, and that no token, device code or credential content reaches stdout or stderr. It
also checks a symlinked home (a junction on Windows) works and a symlinked credential file is refused. Only
synthetic secrets are used, and they are scrubbed from the logs it writes.

## Run in GitHub Actions

The workflow `.github/workflows/signin-os-acceptance.yml` is manual only (`workflow_dispatch`):

```
gh workflow run signin-os-acceptance.yml --ref <branch> -f os=both    # or os=linux / os=windows
gh run watch
```

GitHub lists a `workflow_dispatch` workflow only once the file exists on the default branch; after that, `--ref`
selects the branch whose plugin and script are tested. On failure the scrubbed logs are uploaded as an artifact.

## Run locally

```
mvn -B -ntp -DskipTests install
python3 src/test/acceptance/signin_os_acceptance.py --log-dir /tmp/signin-acceptance-logs
```

`--repo-local DIR` selects the local repository holding the installed plugin and `--maven-arg ARG` (repeatable)
passes extra Maven options, for example `--maven-arg=-Dmaven.repo.local.tail=$HOME/.m2/repository` to install into a
scratch repository seeded read-only from your own.
