#!/usr/bin/env python3
"""Compiler sign-in acceptance: real `mvn` login, account and logout processes on this OS.

A synthetic device-authorisation and compiler service runs on 127.0.0.1 (port 0) with the same protocol shapes as the
plugin's StubCompilerServer test fixture. Every Maven goal runs as a separate non-interactive process with a temporary
home (-Duser.home, plus HOME/USERPROFILE), so no real sign-in is read or changed. The trusted verification origin is
set for the test only with the plugin's existing user property -Dfluxtion.login.verificationOrigin.

Only synthetic secrets are used. Logs are written with every synthetic secret replaced by a placeholder.

Usage: python3 signin_os_acceptance.py [--plugin-version V] [--repo-local DIR] [--log-dir DIR] [--maven-arg ARG]...
Exit status 0 when every check passes (skipped checks are reported), 1 otherwise.
"""
import argparse
import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

WINDOWS = os.name == "nt"
GA = "com.telamin.fluxtion:fluxtion-maven-plugin"
VERIFY_ORIGIN = "https://signin-acceptance.invalid"
ACCOUNT = "acct-acceptance"
CI_MARKERS = ["CI", "JENKINS_URL", "TF_BUILD", "TEAMCITY_VERSION", "CODEBUILD_BUILD_ID", "BUILDKITE",
              "GITHUB_ACTIONS", "GITLAB_CI", "BITBUCKET_BUILD_NUMBER"]
STRIPPED_ENV = ["MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_CONFIG", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"]
# Synthetic secrets, fresh for every run. None of them is accepted by any real service.
TOKEN = "fxk_acceptance." + secrets.token_hex(16)
DEVICE_CODE = "acceptance-device-" + secrets.token_hex(16)
DECOY_TOKEN = "fxk_decoy." + secrets.token_hex(16)
SECRETS = {TOKEN: "<synthetic-token>", DEVICE_CODE: "<synthetic-device-code>", DECOY_TOKEN: "<synthetic-decoy-token>"}


def scrub(text):
    for value, placeholder in SECRETS.items():
        text = text.replace(value, placeholder)
    return text


def leaked(text, bearer=True):
    found = [placeholder for value, placeholder in SECRETS.items() if value in text]
    if bearer and "Bearer " in text:
        found.append("a Bearer header")
    return found


# ---------------------------------------------------------------- synthetic service

class Service:
    """Device authorisation plus /v1/me, /v1/usage and DELETE /v1/credentials/{id}; one case at a time."""

    def __init__(self):
        self.lock = threading.Lock()
        self.reset("approve")
        service = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _reply(self, status, body=None):
                data = b"" if body is None else json.dumps(body).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if data:
                    self.wfile.write(data)

            def _handle(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
                status, reply = service.answer(self.command, self.path, self.headers.get("Authorization"), body)
                self._reply(status, reply)

            do_GET = do_POST = do_DELETE = _handle

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def reset(self, mode, expires_in=600):
        with self.lock:
            self.mode = mode
            self.expires_in = expires_in
            self.user_code = "ACPT-" + "".join(secrets.choice("BCDFGHJKLMNPQRSTVWXZ") for _ in range(4))
            self.verification_uri = VERIFY_ORIGIN + "/device"
            self.credential_id = "cred-" + secrets.token_hex(4)
            self.shown = threading.Event()  # set once the address and code were seen in Maven's output
            self.approved_after_shown = False
            self.requests = []

    def answer(self, method, path, authorization, body):
        with self.lock:
            bearer_ok = authorization == "Bearer " + TOKEN
            self.requests.append({"method": method, "path": path, "bearer": bearer_ok,
                                  "device_code": DEVICE_CODE in body})
            if method == "POST" and path == "/auth/device/authorize":
                return 200, {"device_code": DEVICE_CODE, "user_code": self.user_code,
                             "verification_uri": self.verification_uri,
                             "verification_uri_complete": self.verification_uri + "?user_code=" + self.user_code,
                             "expires_in": self.expires_in, "interval": 1}
            if method == "POST" and path == "/auth/device/token":
                if DEVICE_CODE not in body:
                    return 400, {"error": "invalid_grant"}
                if self.mode == "expire" or not self.shown.is_set():
                    return 400, {"error": "authorization_pending"}
                if self.mode == "deny":
                    return 400, {"error": "access_denied"}
                self.approved_after_shown = True
                return 200, {"access_token": TOKEN, "token_type": "Bearer", "credential_id": self.credential_id,
                             "account_id": ACCOUNT}
            if method == "GET" and path == "/v1/me":
                return (200, {"selectedAccount": ACCOUNT}) if bearer_ok else (401, {"error": "AUTH_REQUIRED"})
            if method == "GET" and path == "/v1/usage":
                if not bearer_ok:
                    return 401, {"error": "AUTH_REQUIRED"}
                return 200, {"accountId": ACCOUNT, "plan": "free", "active": True, "available": 250, "reserved": 0}
            if method == "DELETE" and path.startswith("/v1/credentials/"):
                return (204, None) if bearer_ok else (401, None)
            return 404, {"error": "NOT_FOUND"}

    def seen(self, method, path=None, prefix=None):
        with self.lock:
            return [r for r in self.requests if r["method"] == method
                    and (path is None or r["path"] == path) and (prefix is None or r["path"].startswith(prefix))]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


# ---------------------------------------------------------------- running Maven

class Result:
    def __init__(self, code, out, err):
        self.code, self.out, self.err = code, out, err
        self.all = out + err

    def records(self):
        events = []
        for line in self.out.splitlines():
            at = line.find("FLUXTION-AUTH ")
            if at >= 0:
                try:
                    events.append(json.loads(line[at + len("FLUXTION-AUTH "):]))
                except ValueError:
                    pass
        return events


class Harness:
    def __init__(self, args, work):
        self.args = args
        self.work = work
        self.service = Service()
        self.failures = []
        self.skips = []
        self.passes = 0
        self.logs = Path(args.log_dir) if args.log_dir else work / "logs"
        self.logs.mkdir(parents=True, exist_ok=True)
        self.step = 0

    # -- reporting
    def check(self, case, condition, what, detail=""):
        if condition:
            self.passes += 1
            print("PASS  %-22s %s" % (case, what))
        else:
            self.failures.append("%s: %s" % (case, what))
            print("FAIL  %-22s %s%s" % (case, what, ("\n      " + scrub(detail)[-2000:]) if detail else ""))
        sys.stdout.flush()
        return condition

    def skip(self, case, why):
        self.skips.append("%s: %s" % (case, why))
        print("SKIP  %-22s %s" % (case, why))

    # -- environment
    def env(self, home):
        env = {k: v for k, v in os.environ.items()
               if not k.startswith("FLUXTION_") and k not in CI_MARKERS and k not in STRIPPED_ENV}
        env["HOME"] = str(home)
        if WINDOWS:
            env["USERPROFILE"] = str(home)
        return env

    def mvn(self, case, goal, home, extra=()):
        cmd = [self.args.mvn, "-B", "-ntp"] + (["-o"] if not self.args.online else []) + [
            "-Dmaven.repo.local=" + self.args.repo_local,
            "-Duser.home=" + str(home),
            "%s:%s:%s" % (GA, self.args.plugin_version, goal),
            "-Dfluxtion.compilerUrl=" + self.service.url,
        ] + list(extra) + list(self.args.maven_arg)
        cwd = self.work / "cwd"  # outside any project, so no .mvn options apply
        cwd.mkdir(exist_ok=True)
        proc = subprocess.Popen(cmd, cwd=str(cwd), env=self.env(home), stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, encoding="utf-8", errors="replace")
        out, err, combined = [], [], []
        service = self.service

        def pump(stream, sink):
            for line in stream:
                sink.append(line)
                combined.append(line)
                text = "".join(combined)
                if service.user_code in text and service.verification_uri in text:
                    service.shown.set()

        readers = [threading.Thread(target=pump, args=(proc.stdout, out), daemon=True),
                   threading.Thread(target=pump, args=(proc.stderr, err), daemon=True)]
        for r in readers:
            r.start()
        try:
            code = proc.wait(timeout=self.args.timeout)
        except subprocess.TimeoutExpired:
            self.kill(proc)
            code = None
        for r in readers:
            r.join(10)
        result = Result(code, "".join(out), "".join(err))
        self.step += 1
        name = "%02d-%s-%s.log" % (self.step, case, goal)
        (self.logs / name).write_text(scrub("$ " + " ".join(cmd) + "\n--- exit: %s\n--- stdout\n%s--- stderr\n%s"
                                            % (code, result.out, result.err)), encoding="utf-8")
        self.check(case, code is not None, "%s finished within %ss" % (goal, self.args.timeout))
        self.check(case, not leaked(result.all), "%s printed no token, device code or credential content" % goal,
                   "leaked: %s" % leaked(result.all))
        return result

    @staticmethod
    def kill(proc):
        if WINDOWS:
            subprocess.call(["taskkill", "/T", "/F", "/PID", str(proc.pid)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            proc.kill()

    # -- shared checks
    def login_args(self):
        return ["-Dfluxtion.login.browser=false", "-Dfluxtion.login.verificationOrigin=" + VERIFY_ORIGIN]

    def check_prompt(self, case, result):
        s = self.service
        text = result.out
        uri_at, code_at = text.find(s.verification_uri), text.find(s.user_code)
        self.check(case, uri_at >= 0 and code_at >= 0, "verification address and user code printed", text[-1500:])
        self.check(case, s.shown.is_set(), "address and code were visible while the login was still waiting")
        done = text.find("Signed in to ")
        if done >= 0:
            self.check(case, max(uri_at, code_at) < done, "address and code printed before approval completed")

    def check_owner_only(self, case, path):
        if WINDOWS:
            for target in (path, path.parent):
                ok, detail = windows_owner_only(target)
                self.check(case, ok, "%s ACL grants only the current user (protected, not inherited)"
                           % ("credential file" if target == path else "credential directory"), detail)
                icacls = subprocess.run(["icacls", str(target)], capture_output=True, text=True, errors="replace")
                print("      icacls: " + " | ".join(l.strip() for l in icacls.stdout.splitlines() if l.strip()))
        else:
            mode = stat.S_IMODE(os.lstat(path).st_mode)
            self.check(case, stat.S_ISREG(os.lstat(path).st_mode) and mode == 0o600,
                       "credential file is a regular file with mode 0600 (got %o)" % mode)
            dmode = stat.S_IMODE(os.lstat(path.parent).st_mode)
            self.check(case, dmode == 0o700, "credential directory has mode 0700 (got %o)" % dmode)

    def check_stored(self, case, path):
        exists = path.is_file()
        self.check(case, exists, "credential file created at <home>/.fluxtion/credentials")
        if exists:
            content = path.read_text(encoding="utf-8", errors="replace")  # synthetic credential only
            self.check(case, TOKEN in content and self.service.credential_id in content,
                       "stored credential is the one the service issued")
            self.check_owner_only(case, path)

    def new_home(self, name):
        home = self.work / name
        home.mkdir()
        return home

    # -- cases
    def approve_account_logout(self, case, user_home, real_home):
        s = self.service
        s.reset("approve")
        cred = real_home / ".fluxtion" / "credentials"
        r = self.mvn(case, "login", user_home, self.login_args())
        self.check(case, r.code == 0, "login with approval exits 0", r.all[-2000:])
        self.check_prompt(case, r)
        self.check(case, s.approved_after_shown, "service approved only after the code was shown")
        self.check(case, all(x["device_code"] for x in s.seen("POST", "/auth/device/token")),
                   "device code sent only in the token poll body")
        events = [e.get("event") for e in r.records()]
        self.check(case, events[:1] == ["pending"] and events[-1:] == ["signed_in"],
                   "sign-in records: pending then signed_in (got %s)" % events)
        self.check_stored(case, cred)

        r = self.mvn(case, "account", user_home)
        self.check(case, r.code == 0, "account exits 0", r.all[-2000:])
        self.check(case, "Account ID (server verified): " + ACCOUNT in r.out, "account reports the verified account")

        before = len(s.seen("DELETE", prefix="/v1/credentials/"))
        r = self.mvn(case, "logout", user_home)
        self.check(case, r.code == 0, "logout exits 0", r.all[-2000:])
        deletes = s.seen("DELETE", "/v1/credentials/" + s.credential_id)
        self.check(case, len(deletes) == 1 and deletes[0]["bearer"] and before == 0,
                   "logout revoked the credential (service saw one authenticated DELETE)")
        self.check(case, not cred.exists() and not os.path.lexists(cred), "logout removed the credential file")

    def refused(self, case, mode, expected_event, **reset):
        s = self.service
        s.reset(mode, **reset)
        home = self.new_home(case)
        r = self.mvn(case, "login", home, self.login_args())
        self.check(case, r.code not in (0, None), "login exits non-zero", r.all[-2000:])
        self.check_prompt(case, r)
        events = [e.get("event") for e in r.records()]
        self.check(case, events[-1:] == [expected_event], "final sign-in record is %s (got %s)" % (expected_event, events))
        cred = home / ".fluxtion" / "credentials"
        self.check(case, not os.path.lexists(cred), "no credential file written")
        self.check(case, not s.seen("DELETE", prefix="/v1/credentials/"), "nothing to revoke was issued")

    def origin_required(self):
        """Without the test-only override a loopback service has no trusted verification origin: refuse, print nothing."""
        case = "no-origin-override"
        s = self.service
        s.reset("approve")
        home = self.new_home(case)
        r = self.mvn(case, "login", home, ["-Dfluxtion.login.browser=false"])
        self.check(case, r.code not in (0, None), "login without a trusted origin exits non-zero", r.all[-2000:])
        self.check(case, s.user_code not in r.out and s.verification_uri not in r.out,
                   "untrusted address and code were not printed")
        self.check(case, not s.seen("POST", "/auth/device/token"), "no token poll was sent")
        self.check(case, not os.path.lexists(home / ".fluxtion" / "credentials"), "no credential file written")

    def aliased_home(self):
        case = "junction-home" if WINDOWS else "symlinked-home"
        real = self.new_home("real-home")
        alias = self.work / "alias-home"
        try:
            if WINDOWS:
                made = subprocess.run(["cmd", "/c", "mklink", "/J", str(alias), str(real)],
                                      capture_output=True, text=True, errors="replace")
                if made.returncode != 0:
                    raise OSError(made.stdout + made.stderr)
            else:
                os.symlink(str(real), str(alias), target_is_directory=True)
        except OSError as e:
            self.skip(case, "this runner cannot create a %s: %s" % ("junction" if WINDOWS else "symlink", e))
            return
        self.approve_account_logout(case, alias, real)

    def linked_credential_file(self):
        case = "symlinked-credential"
        s = self.service
        s.reset("approve")
        home = self.new_home(case)
        directory = home / ".fluxtion"
        directory.mkdir()
        if not WINDOWS:
            os.chmod(directory, 0o700)
        target = self.work / "elsewhere-credentials"
        decoy = "token=%s\ncredential_id=cred-decoy\naccount_id=%s\nurl=%s\n" % (DECOY_TOKEN, ACCOUNT, s.url)
        target.write_text(decoy, encoding="utf-8")
        link = directory / "credentials"
        try:
            os.symlink(str(target), str(link))
        except (OSError, NotImplementedError) as e:
            self.skip(case, "this runner cannot create a file symbolic link (needs developer mode or admin): %s" % e)
            return
        r = self.mvn(case, "login", home, self.login_args())
        self.check(case, r.code not in (0, None), "login refuses a symlinked credential file", r.all[-2000:])
        self.check(case, not s.seen("POST", "/auth/device/authorize"), "login sent no sign-in request")
        r = self.mvn(case, "account", home)
        self.check(case, r.code not in (0, None), "account refuses a symlinked credential file", r.all[-2000:])
        self.check(case, not any(x["bearer"] for x in s.requests), "the linked credential was never sent")
        r = self.mvn(case, "logout", home)
        self.check(case, r.code not in (0, None), "logout refuses a symlinked credential file", r.all[-2000:])
        self.check(case, not s.seen("DELETE", prefix="/v1/credentials/"), "logout revoked nothing")
        self.check(case, os.path.islink(link) and target.read_text(encoding="utf-8") == decoy,
                   "link and its target are unchanged")

    def run(self):
        print("Service %s; plugin %s:%s; %s" % (self.service.url, GA, self.args.plugin_version, sys.platform))
        try:
            home = self.new_home("approve")
            self.approve_account_logout("approve", home, home)
            self.refused("deny", "deny", "denied")
            self.refused("expire", "expire", "expired", expires_in=3)
            self.origin_required()
            self.aliased_home()
            self.linked_credential_file()
        finally:
            self.service.close()
        # Final scan: the written logs must not contain any synthetic secret.
        dirty = [p.name for p in self.logs.iterdir() if p.is_file()
                 and leaked(p.read_text(encoding="utf-8", errors="replace"), bearer=False)]
        self.check("logs", not dirty, "written logs are scrubbed", str(dirty))
        print("\n%d passed, %d failed, %d skipped. Logs: %s" % (self.passes, len(self.failures), len(self.skips),
                                                               self.logs))
        for s in self.skips:
            print("  skipped: " + s)
        for f in self.failures:
            print("  failed:  " + f)
        return 1 if self.failures else 0


def windows_owner_only(path):
    """True when the DACL is protected, has no inherited entries and every entry allows only the current user."""
    script = (
        "$ErrorActionPreference='Stop';"
        "$sid=[System.Security.Principal.SecurityIdentifier];"
        "$acl=Get-Acl -LiteralPath $env:ACCEPT_ACL_PATH;"
        "$rules=@($acl.GetAccessRules($true,$true,$sid)|ForEach-Object{@{sid=$_.IdentityReference.Value;"
        "type=$_.AccessControlType.ToString();inherited=$_.IsInherited;rights=$_.FileSystemRights.ToString()}});"
        "@{me=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value;"
        "protected=$acl.AreAccessRulesProtected;rules=$rules}|ConvertTo-Json -Depth 4 -Compress")
    env = dict(os.environ, ACCEPT_ACL_PATH=str(path))
    done = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                          capture_output=True, text=True, env=env, errors="replace")
    if done.returncode != 0:
        return False, "Get-Acl failed: " + done.stderr
    try:
        acl = json.loads(done.stdout)
    except ValueError:
        return False, "unreadable Get-Acl output: " + done.stdout
    rules = acl.get("rules") or []
    if isinstance(rules, dict):
        rules = [rules]
    # The plugin installs a protected DACL with one entry for the current user: SYSTEM and Administrators are not
    # granted, so they are not allowed here either.
    ok = bool(acl.get("protected")) and bool(rules) and all(
        r.get("sid") == acl.get("me") and r.get("type") == "Allow" and not r.get("inherited") for r in rules)
    return ok, json.dumps(acl)


def pom_version(pom):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    return ET.parse(str(pom)).getroot().find("m:version", ns).text.strip()


def main():
    here = Path(__file__).resolve()
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--plugin-version", default=None, help="default: the version in this checkout's pom.xml")
    parser.add_argument("--mvn", default=shutil.which("mvn.cmd" if WINDOWS else "mvn") or "mvn")
    parser.add_argument("--repo-local", default=str(Path.home() / ".m2" / "repository"),
                        help="local repository holding the installed plugin (default: ~/.m2/repository)")
    parser.add_argument("--maven-arg", action="append", default=[], help="extra argument for every mvn process")
    parser.add_argument("--online", action="store_true", help="do not pass -o to Maven")
    parser.add_argument("--log-dir", default=None, help="where scrubbed logs go (default: inside the work directory)")
    parser.add_argument("--timeout", type=int, default=240, help="seconds per Maven process")
    parser.add_argument("--keep", action="store_true", help="keep the temporary work directory")
    args = parser.parse_args()
    if args.plugin_version is None:
        args.plugin_version = pom_version(here.parents[3] / "pom.xml")
    work = Path(tempfile.mkdtemp(prefix="signin-acceptance-"))
    try:
        return Harness(args, work).run()
    finally:
        if args.keep:
            print("Work directory kept: " + str(work))
        else:
            shutil.rmtree(str(work), ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
