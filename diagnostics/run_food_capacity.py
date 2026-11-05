"""Own-process, bounded runner for the frozen successful-cache repair batch."""
import pathlib,datetime,json,hashlib,subprocess,sys,time,shutil,re
ROOT=pathlib.Path(r'C:/Users/jgeted/Desktop/A-Idea');WORK=ROOT/'increments/settlement-food-capacity-20261031/workspace';OUT=ROOT/'deliverables/A-Idea-Settlement-Food-Capacity-20261031'
DEADLINE=datetime.datetime(2026,10,31,5,24,40,tzinfo=datetime.timezone.utc).timestamp()
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def write(p,x):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def run(name,command,limit):
 folder=OUT/name;folder.mkdir(exist_ok=True);assert not (folder/'console.log').exists();start=time.time();meta={'name':name,'command':command,'cwd':str(WORK),'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'maxWallSeconds':limit,'sourceHashes':{str(p.relative_to(WORK)):sha(p) for p in [WORK/'src/main/java/com/jgeted/sagadyssey/social/growth/SettlementGrowthData.java',WORK/'src/main/java/com/jgeted/sagadyssey/worldsim/transfer/TransferLedgerData.java',WORK/'src/main/java/com/jgeted/sagadyssey/social/growth/SettlementGrowthCacheBindingGameTests.java']}}
 write(folder/'COMMAND.json',meta)
 with (folder/'console.log').open('w',encoding='utf-8') as log:
  proc=subprocess.Popen(command,cwd=WORK,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  while proc.poll() is None:
   if time.time()>=min(DEADLINE,start+limit):
    subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],capture_output=True);meta['timeout']=True;break
   time.sleep(.25)
  if proc.poll() is None:proc.wait(timeout=15)
 meta['exitCode']=proc.returncode;meta['wallSeconds']=round(time.time()-start,3);meta['finishedUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();text=(folder/'console.log').read_text(encoding='utf-8',errors='replace')
 meta['selected']=re.findall(r'(?:CACHE|FOODCAP)_SELECTED case=(\w+) expected=1',text);meta['runningCounts']=[int(x) for x in re.findall(r'(\d+) tests are now running',text)];meta['businessResults']=[{'case':a,'status':b,'elapsedTicks':int(c),'reason':d} for a,b,c,d in re.findall(r'(?:CACHE|FOODCAP)_RESULT case=(\w+) result=(\S+) elapsed=(\d+) why=([^\n]*)',text)];meta['logSha256']=sha(folder/'console.log');write(folder/'RESULT.json',meta);return meta
def main():
 spec=json.loads((WORK/'spec/food-capacity.json').read_text(encoding='utf-8'));results=[]
 index=WORK/'src/main/java/com/jgeted/sagadyssey/social/growth'
 assert 'phase.startsWith("counter-")' in (index/'SettlementDevelopmentGameTests.java').read_text(encoding='utf-8')
 assert 'startsWith("formal-")' in (index/'SettlementDevelopmentContinuityGameTests.java').read_text(encoding='utf-8')
 build=run('candidate-build',[str(WORK/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','jar','verifyProductionJar','--console=plain'],120)
 if build['exitCode']!=0:raise SystemExit('candidate build failed')
 write(OUT/'CANDIDATE.json',{'jar':str(WORK/'build/libs/sagadyssey-1.9.0.jar'),'sha256':sha(WORK/'build/libs/sagadyssey-1.9.0.jar'),'sources':build['sourceHashes'],'notPublished':True})
 checkpoint=ROOT/'increments/settlement-housing-v8-20261029/workspace/run/phase9-gametest/development-formal-02/world-growth-checkpoint-2'
 checkpointHashes=json.loads((ROOT/'deliverables/A-Idea-Settlement-Decision-Design-20261030/supplement-v1.1-20261030/P2-checkpoint-exact-state.json').read_text(encoding='utf-8'))['fullSourceWorldFileHashes']
 for case in spec['cases']+spec['regressions']:
  if time.time()>DEADLINE-40:results.append({'name':case['id'],'status':'NOT_RUN_BUDGET'});continue
  started=time.time();game=WORK/'run/phase9-gametest'/case['runLabel'];assert not game.exists();game.mkdir(parents=True)
  if (case['id'].startswith('F') or case['id'] in ['N01','N02','N03','N04','N07']):
   shutil.copytree(checkpoint,game/'world');assert all(sha(game/'world'/rel)==z for rel,z in checkpointHashes.items())
  write(OUT/case['id']/'PREPARE.json',{'profile':case,'initialWorld':str(game/'world'),'fullCopyVerified':(case['id'].startswith('F') or case['id'] in ['N01','N02','N03','N04','N07']),'preparationWallSeconds':time.time()-started})
  command=[str(WORK/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','runGameTestServer','-PdevelopmentOnly=true','-PdevelopmentPhase='+case['phase'],'-Pphase9GameTestRun='+case['runLabel'],'--console=plain']
  result=run(case['id'],command,max(1,case['maxWallSeconds']-(time.time()-started)));results.append(result);write(OUT/'RUN-PROGRESS.json',results);print(case['id'],result['exitCode'],result['wallSeconds'],result['businessResults'],flush=True)
  if result['selected']!=[case['id']] or result['runningCounts']!=[1]:print('selector/harness startup failed, stopping dependent queue',flush=True);break
 print('frozen queue finished',flush=True)
if __name__=='__main__':main()
