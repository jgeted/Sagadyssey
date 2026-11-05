"""Read-only stopped-world evidence; never treated as final acceptance."""
from pathlib import Path
import json,re,hashlib,datetime
from audit_final_growth import W,O,R,nbtlib,plain,u,chunks
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(n,x):(O/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert (O/'stage4/RESULT.json').exists()
r=json.loads((O/'stage4/RESULT.json').read_text(encoding='utf-8'));assert r.get('timeout') and not r['finalAccept']
s=(O/'stage4/console.log').read_text(encoding='utf-8',errors='replace');line=[x for x in s.splitlines() if 'GROWTH_FORMAL_PROGRESS' in x][-1]
ps=nbtlib.parse_nbt(line.split(' projects=',1)[1]);elapsed=int(re.search(r'elapsed=(\d+)',line)[1])
projects=[{'lot':int(x['Lot']),'created':int(x['Created']),'built':int(x['Built']),'constructionTicks':int(x['Built'])-int(x['Created']),'state':str(x['State']),'stableTicks':int(x['StableTicks']),'completedFoodCycles':int(x['CompletedFoodCycles'])} for x in ps]
world=W/'run/phase9-gametest/final-growth-55a-04/world';initial=nbtlib.load(W/'run/phase9-gametest/final-growth-55a-01/world/development-initial.nbt');entities=[]
for p in sorted((world/'entities').glob('*.mca')):
 for c in chunks(p):entities.extend(c.get('Entities',[]))
npcs=[x for x in entities if 'CharacterId' in x];a=sorted((u(x['Person']),u(x['Body'])) for x in initial['Bodies']);b=sorted((u(x['CharacterId']),u(x['UUID'])) for x in npcs)
level=nbtlib.load(world/'level.dat')['Data'];dev=nbtlib.load(world/'data/sagadyssey_settlement_development_v1.dat')['data'];apps=dev.get('Applications',[])
fields={}
for key,name in [('needs','sagadyssey_need_requests_v3.dat'),('routes','sagadyssey_supply_flow_v3.dat'),('rights','sagadyssey_asset_rights_v2.dat'),('households','sagadyssey_households.dat')]:
 p=world/'data'/name;d=nbtlib.load(p)['data'];fields[key]={'source':str(p),'sha256':sha(p),'savedState':plain(d)}
write('STOPPED-WORLD-RESPONSIBILITY.json',fields)
write('PARTIAL-OBSERVATIONS.json',{'status':'BUDGET_BLOCKED_NOT_BUSINESS_FAIL_NOT_FINAL_PASS','candidate':r['candidateSha256'],'lastLogElapsed':elapsed,'lastLogProjects':projects,'lastLogMissingSecondStableTicks':72000-projects[1]['stableTicks'],'actualFullProcessCheckpointCount':3,'originalPeopleAtStart':a,'savedPeopleAfterStop':b,'sameOriginal9PersonBodyPairsInSavedWorld':len(b)==9 and a==b,'savedLevelTime':int(level['Time']),'lastLogTimeAndAutosaveNotAssumedIdentical':True,'savedDevelopmentApplications':plain(apps),'stoppedWorldNormallyClosed':False,'termination':'frozen wrapper taskkill at1200.562seconds; preserve all files; not a normal save-exit success','noGameTestBusinessExceptionReported':not r['failures'],'finalMaterialAudit':'NOT AVAILABLE: final business snapshot absent; do not substitute autosave or earlier checkpoint','normalResumeSafety':'Do not blindly restart this interrupted world. The original harness only supports exact full checkpoints with matching observer metadata. Resume next approved bounded attempt from preserved checkpoint3 in a new directory; do not replay earlier setup/checkpoints or change game criteria.'})
checks=[]
for name in ['A-Idea-Settlement-Food-Capacity-20261031','A-Idea-N07-Responsibility-Evidence-20261031']:
 p=R/'deliverables'/name;m=json.loads((p/'MANIFEST.json').read_text(encoding='utf-8'));files=m.get('files',m)
 # Preserve and verify sealed payload, rather than relying only on a handful of source hashes.
 bad=[k for k,h in files.items() if isinstance(h,str) and (not (p/k).is_file() or sha(p/k)!=h)]
 checks.append({'package':str(p),'checkedFiles':len(files),'differences':bad});assert not bad
write('OLD-SEALS-UNCHANGED.json',checks)
print(json.dumps({'projects':projects,'sameOriginal9':a==b,'stoppedLevelTime':int(level['Time']),'oldSealsUnchanged':True},ensure_ascii=False))
