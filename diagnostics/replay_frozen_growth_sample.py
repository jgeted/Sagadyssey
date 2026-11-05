"""One preselected old trajectory; executes unchanged old run function, no new model rules."""
import argparse,csv,datetime,hashlib,json,pathlib,sys,time
import numpy as np
from prepare_v11_cases import OUT,WORK,write,sha
def main():
 ap=argparse.ArgumentParser()
 for x in ['source-model','calibration','group','policy','out']:ap.add_argument('--'+x,required=True)
 ap.add_argument('--seed',type=int,required=True);ap.add_argument('--samples',type=int,required=True);ap.add_argument('--days',type=int,required=True);ap.add_argument('--compare-existing',action='store_true');a=ap.parse_args()
 spec=json.loads((WORK/'spec/first-batch-v11.json').read_text(encoding='utf-8'));f=spec['modelCase'];p=pathlib.Path(a.source_model);cp=pathlib.Path(a.calibration);out=pathlib.Path(a.out)
 assert a.seed==20261029000000 and a.samples==1 and a.days==300 and a.group=='normal' and a.policy=='capacity_guard'
 assert p.resolve()==pathlib.Path(f['sourceModel']).resolve() and sha(p)==f['sourceModelSha256']
 assert cp.resolve()==pathlib.Path(f['calibration']).resolve() and sha(cp)==f['calibrationSha256']
 assert out.resolve()==(OUT/'M01').resolve()
 started=time.time()
 if a.compare_existing:
  assert out.is_dir() and (out/'INPUT.json').is_file() and not (out/'RESULT.json').exists()
 else:
  assert not out.exists();out.mkdir();write(out/'INPUT.json',{'spec':f,'modelSource':str(p),'originalAlgorithmSha256':sha(p),'calibrationSha256':sha(cp),'onlyNRestrictedToOne':True,'numpy':np.__version__,'python':sys.version,'newIndependentSamples':0,'pairedWithNative':False,'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()})
  ns={'__file__':str(p),'__name__':'frozen_v8_replay'};exec(compile(p.read_text(encoding='utf-8'),str(p),'exec'),ns)
  calibration=json.loads(cp.read_text(encoding='utf-8'));assert calibration['nativeAuditPassed'] and calibration['candidateJar']==spec['sourceCandidateJar']
  ns['N']=1;ns['DAYS']=300;ns['SERVICE_BY_LOT']=np.array(calibration['equivalentServiceTicksByLot'],dtype=float);ns['WALK_TICKS_BY_LOT']=np.array(calibration['nativeMigrationElapsedTicksByLot'],dtype=float);ns['MEAL_ANCHOR']=float(calibration['observedMealsPerPersonDay'])
  ns['run']('normal','capacity_guard',out)
 comparisons=[]
 for suffix in ['inputs','outcomes','daily','people']:
  name='normal-capacity_guard-'+suffix+'.csv'
  if suffix=='daily':
   comparisons.append({'file':name,'newSha256':sha(out/name),'status':'NOT_COMPARABLE_OLD_DAILY_IS_COHORT_AGGREGATE','allFieldsMatch':None,'notTrajectoryPairEvidence':True});continue
  with (p.parent/name).open(encoding='utf-8-sig',newline='') as h:old=[x for x in csv.DictReader(h) if x['seed']==str(a.seed)]
  with (out/name).open(encoding='utf-8-sig',newline='') as h:new=list(csv.DictReader(h))
  diffs=[]
  if len(old)!=len(new):diffs.append({'oldRows':len(old),'newRows':len(new)})
  for i,(x,y) in enumerate(zip(old,new)):
   if x!=y:diffs.append({'row':i,'fields':{k:{'old':x.get(k),'new':y.get(k)} for k in x.keys()|y.keys() if x.get(k)!=y.get(k)}})
  comparisons.append({'file':name,'oldFile':str(p.parent/name),'oldFileSha256':sha(p.parent/name),'newSha256':sha(out/name),'rows':len(new),'allFieldsMatch':not diffs,'differences':diffs})
 elapsed=time.time()-started;result={'status':'PASS' if all(x['allFieldsMatch'] is not False for x in comparisons) else 'FAIL','comparisons':comparisons,'comparisonWallSeconds':elapsed,'sameOldTrajectoryOnly':True,'productionRulesChanged':False,'sourceModelUnchanged':sha(p)==f['sourceModelSha256'],'outcomes':list(csv.DictReader((out/'normal-capacity_guard-outcomes.csv').open(encoding='utf-8-sig')))}
 write(out/'RESULT.json',result);print(json.dumps(result,ensure_ascii=False))
 if result['status']!='PASS':raise SystemExit(1)
if __name__=='__main__':main()
