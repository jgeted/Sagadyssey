"""Seal actual bounded results. No tests, repairs, old-world writes or Git operations."""
from pathlib import Path
import json,hashlib,zipfile,shutil,datetime,xml.etree.ElementTree as ET,difflib
R=Path(r'C:/Users/jgeted/Desktop/A-Idea');W=R/'increments/settlement-food-capacity-20261031/workspace';O=R/'deliverables/A-Idea-Settlement-Food-Capacity-20261031'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
 b=json.loads((O/'BASELINE.json').read_text(encoding='utf-8'));old=Path(b['source']);spec=json.loads((O/'FROZEN-SPEC.json').read_text(encoding='utf-8'));results=json.loads((O/'RUN-PROGRESS.json').read_text(encoding='utf-8'));assert len(results)==17
 allowed='src/main/java/com/jgeted/sagadyssey/social/growth/SettlementGrowthData.java'
 assert all(sha(old/p)==h for p,h in b['inputs'].items());changed=[p for p,h in b['inputs'].items() if sha(W/p)!=h];assert changed==[allowed]
 parent=Path(b['parentEvidence']);pm=json.loads((parent/'MANIFEST.json').read_text(encoding='utf-8'));assert all(sha(parent/p)==h for p,h in pm['files'].items())
 candidates=json.loads((O/'CANDIDATE.json').read_text(encoding='utf-8'));jar=W/'build/libs/sagadyssey-1.9.0.jar';assert sha(jar)==candidates['sha256'];assert sha(O/'before/sagadyssey-1.9.0.jar')==b['jarSha256'];assert sha(O/'before/FOOD-CAPACITY-RECOVERY-DESIGN.md')==b['strategySha256']
 a=zipfile.ZipFile(O/'before/sagadyssey-1.9.0.jar');z=zipfile.ZipFile(jar);assert set(a.namelist())==set(z.namelist());diff=[k for k in a.namelist() if a.read(k)!=z.read(k)];assert diff==['com/jgeted/sagadyssey/social/growth/SettlementGrowthData.class'];assert not any('GameTests' in k and k.endswith('.class') for k in z.namelist())
 codehash=sha(W/allowed);logic=[]
 for p in sorted((W/'build/test-results/test').glob('TEST-*.xml')):
  t=ET.parse(p).getroot();v={'class':t.attrib['name'],'tests':int(t.attrib['tests']),'failures':int(t.attrib['failures']),'errors':int(t.attrib['errors']),'skipped':int(t.attrib['skipped']),'seconds':float(t.attrib['time'])};assert not(v['failures'] or v['errors'] or v['skipped']);logic.append(v);shutil.copy2(p,O/'logic'/p.name) if (O/'logic').exists() else None
 (O/'logic').mkdir(exist_ok=True)
 for p in (W/'build/test-results/test').glob('TEST-*.xml'):shutil.copy2(p,O/'logic'/p.name)
 assert sum(x['tests'] for x in logic)==60
 coverage=[];commands=[];worldIndex=[]
 with zipfile.ZipFile(O/'CLOSED-WORLDS.zip','x',zipfile.ZIP_DEFLATED,compresslevel=3) as arc:
  for case,result in zip(spec['cases']+spec['regressions'],results):
   cid=case['id'];assert result['name']==cid and result['selected']==[cid] and result['runningCounts']==[1];assert all(h==sha(W/p) for p,h in result['sourceHashes'].items());assert result['logSha256']==sha(O/cid/'console.log');assert len(result['businessResults'])==1
   status=result['businessResults'][0]['status'];assert (status=='FAIL')==(cid=='N07');assert result['exitCode']==(1 if cid=='N07' else 0)
   world=W/'run/phase9-gametest'/case['runLabel']/'world';assert world.exists();proof=world/(f'foodcap-{cid}-proof.nbt' if cid.startswith('N') else f'cache-{cid}-proof.nbt');assert proof.exists();shutil.copy2(proof,O/cid/proof.name)
   hashes={}
   for p in sorted(world.rglob('*')):
    if p.is_file():
     rel=p.relative_to(world).as_posix();hashes[rel]=sha(p);arc.write(p,f'{cid}/world/{rel}')
   worldIndex.append({'case':cid,'closedWorld':str(world),'files':hashes,'proof':str(O/cid/proof.name),'archivePrefix':cid+'/world','restoreIsFullOriginalCheckpoint':cid in ['N01','N02','N03','N04','N07','F03','F04'],'completeRestartEvidence':False,'unloadEvidence':False})
   coverage.append({'case':cid,'scope':case['meaning'] if 'meaning' in case else case['mode'],'status':status,'reason':result['businessResults'][0]['reason'],'elapsedTicks':result['businessResults'][0]['elapsedTicks'],'wallSeconds':result['wallSeconds'],'selectorCount':1,'productionSourceHash':codehash,'jarSha256':candidates['sha256'],'sameProductionCandidate':True,'commandRecord':str(O/cid/'COMMAND.json'),'rawLog':str(O/cid/'console.log'),'proof':str(O/cid/proof.name),'notFullRestart':True,'notTrueUnload':True})
   commands.append({'case':cid,'command':result['command'],'cwd':result['cwd'],'exitCode':result['exitCode'],'wallSeconds':result['wallSeconds']})
   shutil.copy2(W/'run/phase9-gametest'/case['runLabel']/'config/sagadyssey-common.toml',O/cid/'sagadyssey-common.toml') if (W/'run/phase9-gametest'/case['runLabel']/'config/sagadyssey-common.toml').exists() else None
 write(O/'WORLD-INDEX.json',worldIndex);write(O/'COVERAGE.json',coverage);write(O/'COMMAND-INDEX.json',commands)
 (O/'source-changes').mkdir(exist_ok=True);extras=['src/main/java/com/jgeted/sagadyssey/social/growth/SettlementFoodCapacityRecoveryGameTests.java','src/test/java/com/jgeted/sagadyssey/social/growth/SettlementFoodCapacityPolicyTest.java','diagnostics/run_food_capacity.py','diagnostics/audit_food_capacity.py','diagnostics/seal_food_capacity.py','spec/food-capacity.json']
 for rel in [allowed]+extras:
  target=O/'source-changes'/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(W/rel,target)
 write(O/'SOURCE-CHANGES.json',{'modifiedExisting':[allowed],'added':extras,'originalLedgerUnchanged':sha(W/'src/main/java/com/jgeted/sagadyssey/worldsim/transfer/TransferLedgerData.java')==b['inputs']['src/main/java/com/jgeted/sagadyssey/worldsim/transfer/TransferLedgerData.java'],'hashes':{p:sha(W/p) for p in [allowed]+extras}})
 sourceFiles=[p for p in W.rglob('*') if p.is_file() and not any(q in ['.gradle','build','run','tmp','.git'] for q in p.relative_to(W).parts)]
 with zipfile.ZipFile(O/'SOURCE.zip','x',zipfile.ZIP_DEFLATED,compresslevel=3) as arc:
  for p in sourceFiles:arc.write(p,p.relative_to(W).as_posix())
 shutil.copy2(jar,O/'sagadyssey-1.9.0.jar')
 fullHashes=json.loads((R/'deliverables/A-Idea-Settlement-Decision-Design-20261030/supplement-v1.1-20261030/P2-checkpoint-exact-state.json').read_text(encoding='utf-8'))['fullSourceWorldFileHashes'];cw=R/'increments/settlement-housing-v8-20261029/workspace/run/phase9-gametest/development-formal-02/world-growth-checkpoint-2';assert all(sha(cw/k)==h for k,h in fullHashes.items())
 version={'baselineJar':b['jarSha256'],'finalJar':sha(jar),'jarEntriesChanged':diff,'onlyAllowedProductionChanged':changed,'allOriginalSourceInputsUnchanged':len(b['inputs']),'parentEvidenceFilesUnchanged':len(pm['files']),'originalCheckpointFilesUnchanged':len(fullHashes),'all17NativeCommandsSameProductionHash':codehash,'newNativeSourceFinalHash':sha(W/extras[0]),'newLogicSourceFinalHash':sha(W/extras[1]),'configurationAndResourcesUnchanged':True,'persistenceSchemaUnchanged':True,'gitCleanStatusNotClaimed':True,'sourceArchiveSha256':sha(O/'SOURCE.zip'),'closedWorldArchiveSha256':sha(O/'CLOSED-WORLDS.zip')};write(O/'VERSION-CHECK.json',version)
 write(O/'LOGIC-SUMMARY.json',{'total':60,'failed':0,'skipped':0,'suites':logic,'sameProductionSource':codehash,'fourteenNewMethodsInclude64DeterministicGiftCapacityCombinations':True,'notMonteCarlo':True})
 statuses={k:sum(v['status']==k for v in coverage) for k in sorted(set(v['status'] for v in coverage))};write(O/'SUMMARY.json',{'status':'LIMITED_RECOVERY_VERIFIED_ONE_ADDITIONAL_FROZEN_CASE_FAILED_PENDING_REVIEW','baseline':b['jarSha256'],'candidate':candidates['sha256'],'nativeStatusCounts':statuses,'nativeTotal':17,'logicPassed':60,'productionFilesModified':1,'N07FailedNotRetried':True,'fullFrozenSuiteAllPassed':False,'notPublished':True,'noModelPairingOrOldUnsupportedPromotion':True})
 print('sealed artifacts ready',statuses,'source files',len(sourceFiles),flush=True)
if __name__=='__main__':main()
