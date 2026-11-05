"""Final same-production-candidate verification; does not revisit unsupported warm-up inputs."""
import pathlib,json,shutil,time,datetime,sys
import run_cache_binding as base
ROOT=base.ROOT;WORK=base.WORK;BATCH=base.OUT;FINAL=BATCH/'final'
def main():
 spec=json.loads((WORK/'spec/cache-binding-final.json').read_text(encoding='utf-8'));FINAL.mkdir(exist_ok=False);base.OUT=FINAL
 build=base.run('candidate-build',[str(WORK/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','jar','verifyProductionJar','test','--tests','com.jgeted.sagadyssey.worldsim.transfer.TransferServiceTest','--tests','com.jgeted.sagadyssey.social.growth.SettlementCapacityTest','--tests','com.jgeted.sagadyssey.social.growth.SettlementPolicyTest','--tests','com.jgeted.sagadyssey.worldsim.transfer.TransferLedgerIdentityReadTest','--tests','com.jgeted.sagadyssey.social.growth.SettlementGrowthCacheBindingTest','--console=plain'],180)
 if build['exitCode']!=0:raise SystemExit('final build/logic failed, preserve')
 jar=WORK/'build/libs/sagadyssey-1.9.0.jar';shutil.copy2(jar,FINAL/'sagadyssey-1.9.0.jar');base.write(FINAL/'CANDIDATE.json',{'jar':str(FINAL/'sagadyssey-1.9.0.jar'),'sha256':base.sha(jar),'sources':build['sourceHashes'],'notPublished':True})
 checkpoint=ROOT/'increments/settlement-housing-v8-20261029/workspace/run/phase9-gametest/development-formal-02/world-growth-checkpoint-2'
 hashes=json.loads((ROOT/'deliverables/A-Idea-Settlement-Decision-Design-20261030/supplement-v1.1-20261030/P2-checkpoint-exact-state.json').read_text(encoding='utf-8'))['fullSourceWorldFileHashes'];results=[]
 for case in spec['finalCases']:
  if time.time()>base.DEADLINE-40:results.append({'id':case['id'],'status':'NOT_RUN_GLOBAL_BUDGET'});continue
  at=time.time();folder=WORK/'run/phase9-gametest'/case['runLabel'];assert not folder.exists();folder.mkdir(parents=True)
  if case['id'].startswith('F'):
   shutil.copytree(checkpoint,folder/'world');assert all(base.sha(folder/'world'/k)==z for k,z in hashes.items())
  base.write(FINAL/case['id']/'PREPARE.json',{'case':case,'targetWorld':str(folder/'world'),'fullCopySourceUnchanged':True,'preparationSeconds':time.time()-at})
  command=[str(WORK/'gradlew.bat'),'--offline','--no-daemon','--no-configuration-cache','runGameTestServer','-PdevelopmentOnly=true','-PdevelopmentPhase='+case['phase'],'-Pphase9GameTestRun='+case['runLabel'],'--console=plain']
  result=base.run(case['id'],command,max(1,case['remainingWallSeconds']-(time.time()-at)));results.append(result);base.write(FINAL/'RUN-PROGRESS.json',results);print(case['id'],result['exitCode'],result['wallSeconds'],result['businessResults'],flush=True)
  if result['selected']!=[case['id']] or result['runningCounts']!=[1]:print('final selector or startup issue, stop dependent queue',flush=True);break
 print('final verification complete',flush=True)
if __name__=='__main__':main()
