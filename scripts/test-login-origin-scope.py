"""Real Maven trust-scope fixture. Uses only synthetic credentials and an OS-assigned loopback port.
Run after locally installing the candidate plugin; no settings or credential contents are read.
"""
import json, os, subprocess, tempfile, threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

root = Path(tempfile.mkdtemp(prefix='login-origin-scope-'))
state = {'requests': []}
class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def reply(self, status, data):
        data = json.dumps(data).encode(); self.send_response(status)
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
        self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        state['requests'].append(self.path)
        if self.path == '/auth/device/authorize':
            self.reply(200, dict(device_code='synthetic-device-secret', user_code='DEMO-CODE',
                                verification_uri='https://login.example.test/device',
                                verification_uri_complete='https://login.example.test/device?code=DEMO-CODE',
                                expires_in=30, interval=1))
        elif self.path == '/auth/device/token':
            self.reply(200, dict(access_token='synthetic-credential-secret', credential_id='cred-demo', account_id='acct-demo'))
        else: self.reply(404, {})
    def do_GET(self):
        state['requests'].append(self.path)
        self.reply(200, {'selectedAccount': 'acct-demo'} if self.path == '/v1/me' else
                   {'accountId': 'acct-demo', 'plan': 'free', 'available': 1})
server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
threading.Thread(target=server.serve_forever, daemon=True).start()
env = {k: v for k, v in os.environ.items() if not k.startswith(('FLUXTION_', 'MAVEN_', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'))
       and k not in ['CI','JENKINS_URL','TF_BUILD','TEAMCITY_VERSION','CODEBUILD_BUILD_ID','BUILDKITE','GITHUB_ACTIONS','GITLAB_CI','BITBUCKET_BUILD_NUMBER']}
origin = 'http://127.0.0.1:' + str(server.server_port)
settings = root / 'settings.xml'; settings.write_text('<settings/>')
try:
    for mode in ['pom-configuration', 'pom-property', 'project-maven-config', 'project-jvm-config', 'cli', 'jvm-user']:
        directory = root / mode; directory.mkdir(); home = directory / 'home'; home.mkdir()
        state['requests'] = []
        property_xml = '<properties><fluxtion.login.verificationOrigin>https://login.example.test</fluxtion.login.verificationOrigin></properties>' if mode == 'pom-property' else ''
        config = '<verificationOrigin>https://login.example.test</verificationOrigin>' if mode == 'pom-configuration' else ''
        (directory / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            '<groupId>example</groupId><artifactId>trust-fixture</artifactId><version>1</version>' + property_xml +
            '<build><plugins><plugin><groupId>com.telamin.fluxtion</groupId><artifactId>fluxtion-maven-plugin</artifactId>'
            '<version>1.3.5-SNAPSHOT</version><configuration><compilerUrl>' + origin + '</compilerUrl>' + config +
            '</configuration></plugin></plugins></build></project>')
        command = ['mvn', '-o', '-B', '-ntp', '-s', str(settings), '-gs', str(settings),
                   'com.telamin.fluxtion:fluxtion-maven-plugin:1.3.5-SNAPSHOT:login',
                   '-Dfluxtion.credentialsFile=' + str(home / '.fluxtion' / 'credentials'),
                   '-Dfluxtion.login.browser=false']
        run_env = dict(env)
        if mode.startswith('project-'):
            (directory / '.mvn').mkdir()
            filename = 'maven.config' if mode == 'project-maven-config' else 'jvm.config'
            (directory / '.mvn' / filename).write_text('-Dfluxtion.login.verificationOrigin=https://login.example.test\n')
        if mode == 'cli': command.append('-Dfluxtion.login.verificationOrigin=https://login.example.test')
        if mode == 'jvm-user': run_env['MAVEN_OPTS'] = '-Dfluxtion.login.verificationOrigin=https://login.example.test'
        result = subprocess.run(command, cwd=directory, env=run_env, capture_output=True, text=True, timeout=45)
        output = result.stdout + result.stderr
        assert 'synthetic-device-secret' not in output and 'synthetic-credential-secret' not in output
        (root / (mode + '.log')).write_text(output)
        allowed = mode in ['cli', 'jvm-user']
        assert (result.returncode == 0) == allowed, mode + ': unexpected Maven outcome; evidence ' + str(root)
        if allowed:
            assert '/auth/device/token' in state['requests'] and '"event":"signed_in"' in output
        else:
            assert '/auth/device/token' not in state['requests'] and '"event":"pending"' not in output
        print('PASS:', mode, 'accepted' if allowed else 'refused without browser handoff or polling')
    print('Evidence:', root)
finally:
    server.shutdown(); server.server_close()
