"""One approved replay of stage4 from checkpoint3, with both clocks preserved."""
from pathlib import Path
import json,hashlib,zipfile,datetime,time,subprocess,shutil,re
R=Path(r'C:/Users/jgeted/Desktop/A-Idea');W=R/'increments/final-two-round-growth-55a31d-20261031/workspace';OLD=R/'deliverables/A-Idea-Final-Two-Round-Growth-20261031';O=R/'deliverables/A-Idea-Final-Growth-Resume-20261031-1535'
START=datetime.datetime(2026,10,31,7,35,9,tzinfo=datetime.timezone.utc).timestamp()
PRIOR=38.261 # prior sealed36.261min + conservative2min approval-preparation charge
DEADLINE=START+(90-PRIOR)*60;LABEL='final-growth-55a-05'
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def write(n,x):(O/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
 assert not (O/'FROZEN-EXECUTION.json').exists(),'one replay only'
 c=json.loads((OLD/'FROZEN-CONTRACT.json').read_text(encoding='utf-8'))
 assert all(sha(W/k)==v for k,v in c['inputFiles'].items());assert sha(W/c['testSource'])==c['testSourceHash']
 assert sha(OLD/'sagadyssey-1.9.0.jar')==c['candidate']
 oldmanifest=json.loads((OLD/'MANIFEST.json').read_text(encoding='utf-8'));assert all(sha(OLD/k)==v for k,v in oldmanifest.items())
 with zipfile.ZipFile(OLD/'sagadyssey-1.9.0.jar') as z:
  entries=[n for n in z.namelist() if n.endswith('.class')];assert all(sha(W/'build/classes/java/main'/n)==hashlib.sha256(z.read(n)).hexdigest() for n in entries)
 source=W/'run/phase9-gametest/final-growth-55a-03/world-growth-checkpoint-3'
 hashes=json.loads((OLD/'stage4-LINEAGE.json').read_text(encoding='utf-8'))['fileHashes'];assert all(sha(source/k)==v for k,v in hashes.items())
 dest=W/'run/phase9-gametest'/LABEL;assert not dest.exists();dest.mkdir();shutil.copytree(source,dest/'world');assert all(sha(dest/'world'/k)==v for k,v in hashes.items())
 shutil.copy2(OLD/'sagadyssey-1.9.0.jar',O/'sagadyssey-1.9.0.jar');shutil.copy2(OLD/'SOURCE.zip',O/'SOURCE.zip')
 write('FROZEN-EXECUTION.json',{'approved':'user explicitly approves one stage4 replay; approval waits excluded, cumulative active90min unchanged','candidate':c['candidate'],'sourceWorkspace':str(W),'originalContract':str(OLD/'FROZEN-CONTRACT.json'),'originalContractHash':sha(OLD/'FROZEN-CONTRACT.json'),'testSource':c['testSource'],'testSourceHash':c['testSourceHash'],'matchingProductionClasses':len(entries),'originalInputFilesChecked':len(c['inputFiles']),'previousActiveMinutesCharged':PRIOR,'currentActiveStartUtc':datetime.datetime.fromtimestamp(START,datetime.timezone.utc).isoformat(),'activeDeadlineUtc':datetime.datetime.fromtimestamp(DEADLINE,datetime.timezone.utc).isoformat(),'processCapSeconds':2100,'originalAbsoluteDeadlineUtc':c['deadlineUtc'],'wallAndActiveClocksSeparate':True,'noChangeToGameCriteria':True,'oldN07FailRetained':True,'newWorldLabel':LABEL,'sourceCheckpoint':str(source),'checkpointTime':74040,'checkpointElapsed':73839,'neverResumeFromLastLog':True,'oneReplayNoRetry':True,'productionModified':False,'runnerSha256':sha(Path(__file__))})
 write('LINEAGE.json',{'source':str(source),'target':str(dest/'world'),'fileHashes':hashes,'sameOriginalWorldCheckpoint':True,'replaysOnlyFourthStage':True,'oldInterruptedStage4Unchanged':True})
 command=[str(W/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','runGameTestServer','-PdevelopmentOnly=true','-PdevelopmentPhase=formal-resume','-Pphase9GameTestRun='+LABEL,'--console=plain']
 start=time.time();meta={'command':command,'cwd':str(W),'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'maxWallSeconds':2100,'activeDeadlineUtc':datetime.datetime.fromtimestamp(DEADLINE,datetime.timezone.utc).isoformat(),'candidate':c['candidate']};write('COMMAND.json',meta)
 with (O/'console.log').open('w',encoding='utf-8') as f:
  p=subprocess.Popen(command,cwd=W,stdout=f,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  while p.poll() is None:
   if time.time()>=min(DEADLINE,start+2100):
    subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True,timeout=20);meta['timeout']=True;break
   time.sleep(.5)
  if p.poll() is None:p.wait(timeout=15)
 s=(O/'console.log').read_text(encoding='utf-8',errors='replace');meta.update({'exitCode':p.returncode,'wallSeconds':round(time.time()-start,3),'finishedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'activeMinutesToResult':round(PRIOR+(time.time()-START)/60,3),'runningCounts':[int(n) for n in re.findall(r'(\d+) tests are now running',s)],'finalAccept':re.findall(r'GROWTH_FINAL_ACCEPT elapsed=(\d+) rounds=2 checkpoints=3 originalPeople=9 exactCarrotBalance=true',s),'failures':re.findall(r'[^\n]*(?:failed at |required tests failed|GameTestAssertException)[^\n]*',s),'logSha256':sha(O/'console.log')});write('RESULT.json',meta)
 print(json.dumps(meta,ensure_ascii=False),flush=True)
if __name__=='__main__':main()
