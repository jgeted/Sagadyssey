"""Final unchanged55a31d candidate, one causal two-round world across trusted checkpoints."""
from pathlib import Path
import json,hashlib,zipfile,datetime,time,subprocess,shutil,re
R=Path(r'C:/Users/jgeted/Desktop/A-Idea');W=R/'increments/final-two-round-growth-55a31d-20261031/workspace';O=R/'deliverables/A-Idea-Final-Two-Round-Growth-20261031';C=json.loads((O/'FROZEN-CONTRACT.json').read_text(encoding='utf-8'));DEADLINE=datetime.datetime.fromisoformat(C['deadlineUtc']).timestamp()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def production():
 jar=O/'sagadyssey-1.9.0.jar';assert sha(jar)==C['candidate']
 with zipfile.ZipFile(jar) as z:
  names=[k for k in z.namelist() if k.endswith('.class')];bad=[k for k in names if not (W/'build/classes/java/main'/k).is_file() or sha(W/'build/classes/java/main'/k)!=hashlib.sha256(z.read(k)).hexdigest()]
 assert not bad,('production bytecode mismatch',bad);return {'jar':sha(jar),'sameProductionClasses':len(names),'testHash':sha(W/C['testSource'])}
def run(name,command,limit):
 d=O/name;d.mkdir(exist_ok=False);start=time.time();meta={'name':name,'command':command,'cwd':str(W),'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'maxWallSeconds':limit,'candidateSha256':C['candidate'],'testSourceHash':sha(W/C['testSource'])};write(d/'COMMAND.json',meta)
 with (d/'console.log').open('w',encoding='utf-8') as log:
  proc=subprocess.Popen(command,cwd=W,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  while proc.poll() is None:
   if time.time()>=min(DEADLINE,start+limit):subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],capture_output=True);meta['timeout']=True;break
   time.sleep(.5)
  if proc.poll() is None:proc.wait(timeout=15)
 text=(d/'console.log').read_text(encoding='utf-8',errors='replace');meta.update({'exitCode':proc.returncode,'wallSeconds':round(time.time()-start,3),'finishedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'logSha256':sha(d/'console.log'),'runningCounts':[int(x) for x in re.findall(r'(\d+) tests are now running',text)],'fullCheckpoint':re.findall(r'GROWTH_FULL_CHECKPOINT next=(\d+)',text),'finalAccept':re.findall(r'GROWTH_FINAL_ACCEPT elapsed=(\d+) rounds=2 checkpoints=3 originalPeople=9 exactCarrotBalance=true',text),'failures':re.findall(r'[^\n]*(?: failed at | required tests failed|GameTestAssertException)[^\n]*',text)});write(d/'RESULT.json',meta);print(name,meta['exitCode'],meta['wallSeconds'],'checkpoints',meta['fullCheckpoint'],'final',meta['finalAccept'],flush=True);return meta

def main():
 assert all(sha(W/k)==h for k,h in C['inputFiles'].items());assert sha(W/C['testSource'])==C['testSourceHash']
 build=run('compile',[str(W/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','compileJava','--console=plain'],180)
 if build['exitCode']!=0:raise SystemExit('compile failed; no retry')
 binding=production();binding['contractSha256']=sha(O/'FROZEN-CONTRACT.json');binding['runnerHash']=sha(W/'diagnostics/run_final_growth.py');write(O/'EXECUTION-BINDING.json',binding)
 results=[]
 for i,label in enumerate(C['labels']):
  if time.time()>=DEADLINE-60:write(O/'STOP.json',{'reason':'global90minute preparation included budget','nextStage':i+1});break
  game=W/'run/phase9-gametest'/label;assert not game.exists();game.mkdir(parents=True)
  if i:
   source=W/'run/phase9-gametest'/C['labels'][i-1]/('world-growth-checkpoint-'+str(i));assert source.is_dir();hashes={p.relative_to(source).as_posix():sha(p) for p in source.rglob('*') if p.is_file()};shutil.copytree(source,game/'world');assert all(sha(game/'world'/k)==v and sha(source/k)==v for k,v in hashes.items());write(O/('stage'+str(i+1)+'-LINEAGE.json'),{'source':str(source),'target':str(game/'world'),'fileHashes':hashes,'sameCausalWorld':True,'freshFixturesCreated':False})
  production();command=[str(W/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','runGameTestServer','-PdevelopmentOnly=true','-PdevelopmentPhase='+C['stages'][i],'-Pphase9GameTestRun='+label,'--console=plain'];result=run('stage'+str(i+1),command,C['maxEachProcessSeconds']);results.append(result);write(O/'RUN-PROGRESS.json',results)
  if result['exitCode']!=0 or result['runningCounts']!=[1]:write(O/'STOP.json',{'stage':i+1,'reason':'first failure or selector mismatch; no retry or production repair'});break
  if i<3 and result['fullCheckpoint']!=[str(i+1)]:write(O/'STOP.json',{'stage':i+1,'reason':'expected real checkpoint missing; checkpoint completion is not final business pass'});break
  if i==3 and not result['finalAccept']:write(O/'STOP.json',{'stage':4,'reason':'no final business accept, do not infer from test return'});break
 print('final milestone run queue ended',flush=True)
if __name__=='__main__':main()
