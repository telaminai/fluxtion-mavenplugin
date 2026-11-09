"""Real Maven logout rendering, using synthetic state and a port-0 local server only.

Install the candidate into an isolated Maven repository first, then pass --repository.
An optional --cache supplies a read-only artifact-cache tail, never Maven settings.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--repository', type=Path, required=True)
parser.add_argument('--cache', type=Path)
args = parser.parse_args()
version = ET.parse(Path(__file__).resolve().parents[1] / 'pom.xml').find('{http://maven.apache.org/POM/4.0.0}version').text
requests = []
class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_DELETE(self):
        requests.append(self.path)
        self.send_response(204)
        self.end_headers()

server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with tempfile.TemporaryDirectory(prefix='logout-render-') as name:
        root = Path(name)
        home = root / 'home'
        home.mkdir()
        store = home / '.fluxtion' / 'credentials'
        store.parent.mkdir()
        synthetic = 'token=synthetic-render-token\ncredential_id=cred-render\naccount_id=acct-render\nurl=http://127.0.0.1:' + str(server.server_port) + '\n'
        store.write_text(synthetic)
        store.chmod(0o600)
        target = root / 'untouched'
        target.write_text('untouched')
        lock = store.with_name('credentials.lock')
        lock.symlink_to(target)
        settings = root / 'settings.xml'
        settings.write_text('<settings/>')
        env = {k: v for k, v in os.environ.items() if not k.startswith(('FLUXTION_', 'MAVEN_', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'))}
        env['HOME'] = str(home)
        env['MAVEN_OPTS'] = '-Duser.home=' + str(home)
        command = ['mvn', '-o', '-B', '-ntp', '-s', str(settings), '-gs', str(settings),
                   '-Dmaven.repo.local=' + str(args.repository.resolve()),
                   'com.telamin.fluxtion:fluxtion-maven-plugin:' + version + ':logout']
        if args.cache: command.append('-Dmaven.repo.local.tail=' + str(args.cache.resolve()))
        result = subprocess.run(command, cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=90)
        output = result.stdout
        assert result.returncode != 0, 'logout unexpectedly succeeded'
        assert requests == ['/v1/credentials/cred-render'], 'fixture did not observe exactly one revocation'
        assert 'Revocation was attempted' in output, 'actionable failure missing'
        assert 'symbolic links' in output and 'credentials.lock' in output, 'local recovery guidance missing'
        assert 'synthetic-render-token' not in output, 'synthetic token leaked'
        assert 'OS error' not in output, 'Maven appended the raw native error'
        assert lock.is_symlink() and target.read_text() == 'untouched'
        assert store.read_text() == synthetic, 'refused deletion changed the stored credential'
        print('PASS: real Maven output is actionable without native-error suffix; link, target and synthetic credential unchanged')
finally:
    server.shutdown()
    server.server_close()
