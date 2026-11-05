"""Archive stopped test worlds and report actual evidence without altering gameplay."""
from pathlib import Path
import json, hashlib, zipfile, datetime, re, collections
R=Path(r'C:/Users/jgeted/Desktop/A-Idea')
W=R/'increments/final-two-round-growth-55a31d-20261031/workspace'
O=R/'deliverables/A-Idea-Final-Two-Round-Growth-20261031'
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
 return h.hexdigest()
def write(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
 assert not (O/'SEAL.json').exists(),'never replace a seal'
 c=json.loads((O/'FROZEN-CONTRACT.json').read_text(encoding='utf-8'))
 results=json.loads((O/'RUN-PROGRESS.json').read_text(encoding='utf-8'))
 assert len(results)==4 or (O/'STOP.json').exists()
 accepted=len(results)==4 and bool(results[-1].get('finalAccept')) and results[-1]['exitCode']==0
 assert sha(O/'sagadyssey-1.9.0.jar')==c['candidate']
 changed=[k for k,v in c['inputFiles'].items() if not (W/k).is_file() or sha(W/k)!=v]
 assert not changed,changed
 with zipfile.ZipFile(O/'sagadyssey-1.9.0.jar') as z:
  entries=[n for n in z.namelist() if n.endswith('.class')]
  assert all(sha(W/'build/classes/java/main'/n)==hashlib.sha256(z.read(n)).hexdigest() for n in entries)
 archives=[]
 for label in c['labels']:
  base=W/'run/phase9-gametest'/label
  if not base.is_dir():continue
  files=[p for p in base.rglob('*') if p.is_file()]
  dst=O/(label+'-worlds.zip');assert not dst.exists()
  hashes={p.relative_to(base).as_posix():sha(p) for p in files}
  with zipfile.ZipFile(dst,'x',zipfile.ZIP_DEFLATED,compresslevel=3) as z:
   for p in files:z.write(p,p.relative_to(base).as_posix())
  with zipfile.ZipFile(dst) as z:
   assert all(hashlib.sha256(z.read(n)).hexdigest()==h for n,h in hashes.items())
  archives.append({'label':label,'archive':dst.name,'sha256':sha(dst),'files':hashes,'normallyClosed':not (label==c['labels'][-1] and results[-1].get('timeout'))})
 write(O/'WORLD-ARCHIVES.json',archives)
 sd=O/'diagnostic-source';sd.mkdir(exist_ok=False)
 newDiagnostics=['run_final_growth.py','audit_final_growth.py','audit_stopped_growth.py','seal_final_growth.py']
 for name in newDiagnostics:
  p=W/'diagnostics'/name;(sd/p.name).write_bytes(p.read_bytes())
 (sd/'SettlementDevelopmentContinuityGameTests.java').write_bytes((W/c['testSource']).read_bytes())
 cfg=[]
 for label in c['labels']:
  base=W/'run/phase9-gametest'/label
  for p in sorted(base.glob('config/*')):
   if not p.is_file():continue
   dst=O/'runtime-config'/label/p.name;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(p.read_bytes());cfg.append({'source':str(p),'copy':str(dst),'sha256':sha(p)})
 write(O/'CONFIG-INDEX.json',cfg)
 s=(O/'stage4/console.log').read_text(encoding='utf-8',errors='replace') if (O/'stage4/console.log').exists() else ''
 last=[x for x in s.splitlines() if 'GROWTH_FORMAL_PROGRESS' in x]
 observed={}
 if last:
  x=last[-1];observed={'lastProgressLine':x,'elapsed':int(re.search(r'elapsed=(\d+)',x)[1]),'stableStates':re.findall(r'StableTicks:(\d+)L,State:"([A-Z_]+)"',x),'cycles':re.findall(r'CompletedFoodCycles:(\d+)',x)}
 write(O/'LAST-OBSERVED-PROGRESS.json',observed)
 native={'candidate':c['candidate'],'nativeFinalAccepted':accepted,'originalFilesCompared':len(c['inputFiles']),'originalFilesChanged':changed,'productionClassEntriesEqual':len(entries),'testSourceHash':sha(W/c['testSource']),'newDiagnosticsOnly':{str(W/'diagnostics'/name):sha(W/'diagnostics'/name) for name in newDiagnostics},'processes':results,'noProductionModification':True,'notPublished':True}
 write(O/'FINAL-VERSION.json',native)
 coverage=[
  {'item':'N07 original','status':'FAIL unchanged','evidence':'separate original sealed package; new tests do not overwrite it'},
  {'item':'N07 S01 changed legal quantity after failed8','status':'PASS','evidence':'../A-Idea-N07-Responsibility-Evidence-20261031/; pick7 then deliver7'},
  {'item':'N07 S02 frozen post-partial new-harvest branch','status':'FAIL','evidence':'normal existing farmer stock2 arrived before partialdrop; actual subsequent2 delivered, new post-partial harvest not covered'},
  {'item':'same final candidate full two-round growth','status':'PASS' if accepted else 'NOT COMPLETED','evidence':'stage1..stage4/RESULT.json; final marker and independent audits required'},
  {'item':'three actual unload/full process restart checkpoints','status':'PASS' if len(results)>=4 else 'PARTIAL','evidence':'stage1..stage3 checkpoint markers and stage2..stage4 lineage/return checks; not anomaly injection matrix'},
  {'item':'strict model-native initial condition pairing','status':'NOT RUN','evidence':'not within this batch'},
  {'item':'save/restart anomaly matrix','status':'NOT RUN','evidence':'normal checkpoints do not substitute'},
  {'item':'open-world / human game experience','status':'NOT RUN','evidence':'independent conversation and worlds, not accessed'},
  {'item':'historical P1-P8 / logic suite','status':'HISTORICAL ONLY','evidence':'not rerun here'},
 ]
 write(O/'COVERAGE.json',coverage)
 now=datetime.datetime.now(datetime.timezone.utc);elapsed=(now-datetime.datetime.fromisoformat(c['startedUtc'])).total_seconds()/60
 write(O/'PRESEAL-CHECK.json',{'utc':now.isoformat(),'elapsedMinutes':round(elapsed,3),'withinGlobal90Minutes':elapsed<=90,'nativeFinalAccepted':accepted})
 files={p.relative_to(O).as_posix():sha(p) for p in sorted(O.rglob('*')) if p.is_file() and p.name not in ['MANIFEST.json','SEAL.json']}
 write(O/'MANIFEST.json',files)
 write(O/'SEAL.json',{'sealedUtc':now.isoformat(),'elapsedMinutes':round(elapsed,3),'nativeFinalAccepted':accepted,'manifestSha256':sha(O/'MANIFEST.json'),'candidate':c['candidate'],'notPublished':True,'noProductionModification':True})
 print(json.dumps({'accepted':accepted,'archiveCount':len(archives),'sealedFiles':len(files),'elapsedMinutes':round(elapsed,3)},ensure_ascii=False))
if __name__=='__main__':main()
