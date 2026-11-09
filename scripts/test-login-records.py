import os,json,subprocess,tempfile,threading
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
root=Path(tempfile.mkdtemp(prefix='agent-login-records-')).resolve();state={'mode':'approved','polls':0}; output_seen=threading.Event()
class Fixture(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def reply(self,status,data):
  data=json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def do_POST(self):
  self.rfile.read(int(self.headers.get('Content-Length',0)))
  if self.path=='/auth/device/authorize': self.reply(200,{'device_code':'synthetic-device-secret','user_code':'DEMO-CODE','verification_uri':'https://login.example.test/device','expires_in':30,'interval':1});return
  assert self.path=='/auth/device/token';state['polls']+=1
  if not output_seen.is_set():self.reply(400,{'error':'authorization_pending'});return
  if state['mode']=='approved':self.reply(200,{'access_token':'synthetic-credential-secret','credential_id':'cred-demo','account_id':'acct-demo'})
  else:self.reply(400,{'error':state['mode']})
 def do_GET(self):
  assert self.path in ['/v1/me','/v1/usage']; self.reply(200,{'selectedAccount':'acct-demo'} if self.path=='/v1/me' else {'accountId':'acct-demo','plan':'free','available':5})
server=ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=server.serve_forever,daemon=True).start()
env={k:v for k,v in os.environ.items() if not k.startswith(('FLUXTION_','MAVEN_','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS')) and k not in ['CI','JENKINS_URL','TF_BUILD','TEAMCITY_VERSION','CODEBUILD_BUILD_ID','BUILDKITE','GITHUB_ACTIONS','GITLAB_CI','BITBUCKET_BUILD_NUMBER']}
env['JAVA_HOME']=os.environ['JAVA_HOME']
try:
 for mode in ['approved','access_denied','expired_token']:
  state.update(mode=mode,polls=0);output_seen.clear();credential=root/mode/'credentials';credential.parent.mkdir()
  cmd=['mvn','-o','-B','-ntp','com.telamin.fluxtion:fluxtion-maven-plugin:1.3.5-SNAPSHOT:login','-Dfluxtion.compilerUrl=http://127.0.0.1:'+str(server.server_port),'-Dfluxtion.credentialsFile='+str(credential),'-Dfluxtion.login.browser=false','-Dfluxtion.login.verificationOrigin=https://login.example.test']
  process=subprocess.Popen(cmd,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  lines=[]
  def collect():
   for line in process.stdout:
    lines.append(line)
    if 'DEMO-CODE' in line:output_seen.set()
  reader=threading.Thread(target=collect);reader.start()
  try:code=process.wait(timeout=60)
  except:process.kill();raise
  reader.join();out=''.join(lines);(root/(mode+'.log')).write_text(out)
  assert 'https://login.example.test/device' in out and output_seen.is_set(),out[-1000:]
  assert 'synthetic-device-secret' not in out and 'synthetic-credential-secret' not in out
  assert (code==0)==(mode=='approved'),out[-1500:]
  assert credential.exists()==(mode=='approved')
  records=[json.loads(line.split('FLUXTION-AUTH ',1)[1]) for line in lines if 'FLUXTION-AUTH ' in line]
  assert len(records)==2,records
  assert records[0]['event']=='pending'
  assert records[1]['event']=={'approved':'signed_in','access_denied':'denied','expired_token':'expired'}[mode]
  assert records[0]['attemptId']==records[1]['attemptId']
  if mode=='approved': assert records[1]['accountId']=='acct-demo' and records[1]['identityStatus']=='verified'
  print('PASS:',mode,'non-TTY URL/code before approval; correct Maven result; no secret output')
 print('Evidence:',root)
finally:server.shutdown();server.server_close()
