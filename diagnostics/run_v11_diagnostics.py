"""Budgeted allowlist-only runner; ordinary game failures are preserved, never retried."""
import argparse,datetime,hashlib,json,os,pathlib,re,subprocess,sys,time,xml.etree.ElementTree as ET
from prepare_v11_cases import ROOT,WORK,OUT,DESIGN,sha,write
START=datetime.datetime(2026,10,30,12,51,23,tzinfo=datetime.timezone.utc).timestamp()
DEADLINE=START+5400
def run(command,name,limit,prepare=False):
 now=time.time()
 if now>=DEADLINE-10:return {'name':name,'status':'NOT_RUN_BUDGET_EXHAUSTED'}
 folder=OUT/name;folder.mkdir(parents=True,exist_ok=True);log=folder/'console.log'
 if log.exists():raise SystemExit('Refuse overwrite '+str(log))
 before={str(p.relative_to(WORK)):sha(p) for p in WORK.glob('src/main/java/**/Settlement*GameTests.java')}
 data={'name':name,'command':command,'cwd':str(WORK),'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'maxWallSeconds':limit,'sourceTests':before}
 write(folder/'COMMAND.json',data)
 with log.open('w',encoding='utf-8') as f:
  p=subprocess.Popen(command,cwd=WORK,stdout=f,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  deadline=min(DEADLINE,now+limit)
  while p.poll() is None:
   if time.time()>deadline:
    # Kill only this isolated spawned build process tree, never user clients.
    subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True)
    data['budgetStopped']=True;break
   time.sleep(.5)
  if p.poll() is None:p.wait(timeout=15)
 data['wallSeconds']=round(time.time()-now,3);data['exitCode']=p.returncode;data['finishedUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 text=log.read_text(encoding='utf-8',errors='replace');matches=re.findall(r'V11_RESULT case=(\w+) result=(\S+) hard=(\S+) elapsed=(\d+)([^\n]*)',text)
 data['businessResults']=[dict(case=a,result=b,hard=c,elapsed=int(d),detail=e) for a,b,c,d,e in matches]
 data['selected']=re.findall(r'V11_SELECTED case=(\w+) expected=1',text)
 data['runningTestCounts']=[int(x) for x in re.findall(r'(\d+) tests are now running',text)]
 data['logSha256']=sha(log);write(folder/'RESULT.json',data);return data
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--cases',nargs='+');a=ap.parse_args();spec=json.loads((WORK/'spec/first-batch-v11.json').read_text(encoding='utf-8'))
 results=[]
 for ident in a.cases or [x['id'] for x in spec['nativeCases']]:
  case=next(x for x in spec['nativeCases'] if x['id']==ident)
  # Inspect every old generator before launch; exact namespace and counter exclusion.
  index=WORK/'src/main/java/com/jgeted/sagadyssey/social/growth'
  assert 'phase.startsWith("counter-")' in (index/'SettlementDevelopmentGameTests.java').read_text(encoding='utf-8')
  assert '"counter-guards","counter-obstruction","counter-withdraw","counter-shortage"' in (index/'SettlementDevelopmentRecoveryGameTests.java').read_text(encoding='utf-8')
  assert 'counter-circulation-v8' in (index/'SettlementHousingCirculationGameTests.java').read_text(encoding='utf-8')
  assert 'startsWith("formal-")' in (index/'SettlementDevelopmentContinuityGameTests.java').read_text(encoding='utf-8')
  command=list(case['command'])
  if ident=='T01':
   # Initial harness run used in-memory copy only; preserve it as early evidence.
   command=[x.replace('v11-t01-20261030-01','v11-t01-20261030-02') for x in command]
  case=dict(case);case['runLabel']=next(x.split('=',1)[1] for x in command if x.startswith('-Pphase9GameTestRun='))
  remaining=DEADLINE-time.time()
  if remaining<35:results.append({'name':ident,'status':'NOT_RUN_BUDGET_INSUFFICIENT'});continue
  # Preparation counts in the same case wall budget.
  started=time.time();target=WORK/'run/phase9-gametest'/case['runLabel']
  if target.exists():raise SystemExit('Refuse existing run '+str(target))
  target.mkdir(parents=True)
  if case['profile'].startswith('P2'):
   import shutil
   origin=pathlib.Path(spec['profiles']['P2']['closedCheckpoint']);shutil.copytree(origin,target/'world')
   old=json.loads((DESIGN/'P2-checkpoint-exact-state.json').read_text(encoding='utf-8'))
   assert all(sha(target/'world'/k)==v for k,v in old['fullSourceWorldFileHashes'].items())
  write(OUT/ident/'PREPARE.json',{'target':str(target),'preparationWallSeconds':time.time()-started,'fullWorldCopy':case['profile'].startswith('P2'),'sourceUnedited':True})
  result=run(command,ident,max(1,case['maxWallSeconds']-(time.time()-started)))
  results.append(result)
  write(OUT/'RUN-PROGRESS.json',{'globalStartUtc':'2026-10-30T12:51:23Z','globalDeadlineUtc':'2026-10-30T14:21:23Z','results':results})
  print(ident,result.get('exitCode'),result.get('wallSeconds'),result.get('businessResults'),flush=True)
  if result.get('selected')!=[ident] or result.get('runningTestCounts')!=[1]:
   print('Selector/startup problem; preserve and stop dependent run queue',flush=True);break
 print('runner complete',flush=True)
if __name__=='__main__':main()
