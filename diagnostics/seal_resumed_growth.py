"""Seal the approved single replay and its actual final audit, never old success substitution."""
from pathlib import Path
import json,hashlib,zipfile,datetime,shutil
R=Path(r'C:/Users/jgeted/Desktop/A-Idea');W=R/'increments/final-two-round-growth-55a31d-20261031/workspace';OLD=R/'deliverables/A-Idea-Final-Two-Round-Growth-20261031';O=R/'deliverables/A-Idea-Final-Growth-Resume-20261031-1535'
def sha(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def read(n):return json.loads((O/n).read_text(encoding='utf-8'))
def write(n,x):(O/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
 assert not (O/'SEAL.json').exists()
 contract=read('FROZEN-EXECUTION.json');result=read('RESULT.json');accepted=result['exitCode']==0 and result['runningCounts']==[1] and bool(result['finalAccept'])
 checks={}
 for n in ['ITEM-BALANCE.json','CLOSED-WORLD-CHECK.json','OWNERSHIP-AND-DUTIES.json']:
  checks[n]=read(n)['passed'] if (O/n).exists() else False
 passed=accepted and all(checks.values())
 auditSpec=read('FROZEN-AUDIT-SPEC.json')
 assert all(sha(W/'diagnostics'/n)==h for n,h in auditSpec['auditScripts'].items()),'preserve frozen audit implementation'
 source=json.loads((OLD/'FROZEN-CONTRACT.json').read_text(encoding='utf-8'));assert all(sha(W/k)==h for k,h in source['inputFiles'].items());assert sha(O/'sagadyssey-1.9.0.jar')==source['candidate']
 with zipfile.ZipFile(O/'sagadyssey-1.9.0.jar') as z:
  entries=[n for n in z.namelist() if n.endswith('.class')];assert all(sha(W/'build/classes/java/main'/n)==hashlib.sha256(z.read(n)).hexdigest() for n in entries)
 preserved=[]
 for name in ['A-Idea-Settlement-Food-Capacity-20261031','A-Idea-N07-Responsibility-Evidence-20261031','A-Idea-Final-Two-Round-Growth-20261031']:
  p=R/'deliverables'/name;m=json.loads((p/'MANIFEST.json').read_text(encoding='utf-8'));files=m.get('files',m);bad=[k for k,h in files.items() if isinstance(h,str) and sha(p/k)!=h];assert not bad
  preserved.append({'package':str(p),'manifestSha256':sha(p/'MANIFEST.json'),'payloadsVerified':len(files),'changed':bad})
 write('PRESERVED-SEALS.json',preserved)
 sd=O/'diagnostic-source';sd.mkdir(exist_ok=False)
 names=['resume_final_growth.py','audit_resumed_growth.py','audit_resumed_responsibility.py','seal_resumed_growth.py']
 for n in names:shutil.copy2(W/'diagnostics'/n,sd/n)
 shutil.copy2(W/source['testSource'],sd/'SettlementDevelopmentContinuityGameTests.java')
 game=W/'run/phase9-gametest/final-growth-55a-05';world=game/'world';files=[p for p in game.rglob('*') if p.is_file()];hashes={p.relative_to(game).as_posix():sha(p) for p in files};archive=O/'FINAL-WORLD.zip';assert not archive.exists()
 with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=3) as z:
  for p in files:z.write(p,p.relative_to(game).as_posix())
 with zipfile.ZipFile(archive) as z:assert all(hashlib.sha256(z.read(k)).hexdigest()==h for k,h in hashes.items())
 write('WORLD-INDEX.json',{'source':str(game),'archive':archive.name,'sha256':sha(archive),'normallyClosed':result['exitCode']==0 and not result.get('timeout',False),'fileHashes':hashes,'predecessorCheckpointArchive':str(OLD/'final-growth-55a-03-worlds.zip'),'originalInterruptedStage4Retained':str(OLD/'final-growth-55a-04-worlds.zip')})
 pd=O/'proofs';pd.mkdir(exist_ok=False)
 for p in [W/'run/phase9-gametest/final-growth-55a-01/world/development-initial.nbt',world/'development-final.nbt',world/'development-before-first-tick.nbt']:
  if p.exists():shutil.copy2(p,pd/p.name)
 cfg=O/'runtime-config';cfg.mkdir(exist_ok=False)
 configs=[]
 for p in sorted((game/'config').glob('*')):
  if p.is_file():shutil.copy2(p,cfg/p.name);configs.append({'path':str(p),'sha256':sha(p)})
 write('CONFIG-INDEX.json',configs)
 now=datetime.datetime.now(datetime.timezone.utc);start=datetime.datetime.fromisoformat(contract['currentActiveStartUtc']);active=contract['previousActiveMinutesCharged']+(now-start).total_seconds()/60
 originalStart=datetime.datetime.fromisoformat(source['startedUtc']);actualWall=(now-originalStart).total_seconds()/60
 write('BUDGET-ACCOUNT.json',{'previousExecutionPreparationSealingMinutes':36.261,'additionalPreparationConservativeChargeMinutes':2,'priorActiveMinutesCharged':contract['previousActiveMinutesCharged'],'newActiveMinutes':round((now-start).total_seconds()/60,3),'cumulativeActiveMinutes':round(active,3),'originalWallMinutesIncludingApprovalWait':round(actualWall,3),'approvalPauseMinutesNotCharged':round(actualWall-active,3),'active90MinuteLimit':active<=90,'newProcessSeconds':result['wallSeconds'],'process35MinuteLimit':result['wallSeconds']<=2105,'originalAbsoluteDeadlineNotPretendedUnchanged':True,'approvedAccounting':'approval waits separate; active cumulative90min; game time unchanged'})
 version={'candidate':source['candidate'],'sourceZipSha256':sha(O/'SOURCE.zip'),'testSourceSha256':sha(W/source['testSource']),'matchingProductionClasses':len(entries),'unchangedOriginalInputs':len(source['inputFiles']),'nativeFinalAccepted':accepted,'independentChecks':checks,'fullMilestonePass':passed,'productionChanges':[],'newDiagnosticFiles':{str(W/'diagnostics'/n):sha(W/'diagnostics'/n) for n in names},'noPushOrPublish':True};write('FINAL-VERSION.json',version)
 coverage=[{'item':'same55a bounded full two-round real growth','status':'PASS' if passed else 'FAIL/INCOMPLETE','evidence':['RESULT.json','BUSINESS-AUDIT.json','ITEM-BALANCE.json','CLOSED-WORLD-CHECK.json','OWNERSHIP-AND-DUTIES.json']}, {'item':'3 full associated unload/restart checkpoints','status':'PASS','evidence':'original stages1-3, unchanged complete checkpoint3, this LINEAGE and before-first-tick comparison; not anomaly injection matrix'}, {'item':'original stage4 timeout','status':'NOT PASS, retained','evidence':str(OLD/'stage4/RESULT.json')}, {'item':'original N07','status':'FAIL unchanged','evidence':'original FOOD-capacity package'}, {'item':'N07 S01 quantity changed8to7','status':'PASS','evidence':'N07-Responsibility package and readonly owner supplement'}, {'item':'N07 S02 partialdrop then newly harvested refill','status':'FAIL retained, branch not reached','evidence':'subsequent2 was existing lawful farmer stock deposited before partialdrop; actual7plus2 real deliveries are separate facts'}, {'item':'strict model-native pairing','status':'NOT RUN'}, {'item':'save/restart anomaly matrix','status':'NOT RUN'}, {'item':'open-world or human game experience','status':'NOT RUN, separate instances'}, {'item':'historical logic/P1-P8','status':'HISTORICAL ONLY, not rerun'}, {'item':'war/birth/classifier/regional styles','status':'OUT OF SCOPE'}];write('COVERAGE.json',coverage)
 assert active<=90,'never relabel budget overrun as within90'
 assert (O/'FINAL-REPORT.md').exists(),'write truthful human report before sealing'
 payload={p.relative_to(O).as_posix():sha(p) for p in sorted(O.rglob('*')) if p.is_file() and p.name not in ['MANIFEST.json','SEAL.json']};write('MANIFEST.json',payload);write('SEAL.json',{'sealedUtc':now.isoformat(),'candidate':source['candidate'],'fullMilestonePass':passed,'manifestSha256':sha(O/'MANIFEST.json'),'cumulativeActiveMinutes':round(active,3),'notPublished':True})
 print(json.dumps({'passed':passed,'checks':checks,'activeMinutes':round(active,3),'payloads':len(payload)},ensure_ascii=False))
if __name__=='__main__':main()
