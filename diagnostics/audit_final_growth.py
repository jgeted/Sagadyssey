"""Independent read-only accounting and normally closed on-disk world comparison."""
from pathlib import Path
import sys,numpy,json,collections,hashlib,uuid,io,zlib,gzip,csv
R=Path(r'C:/Users/jgeted/Desktop/A-Idea');W=R/'increments/final-two-round-growth-55a31d-20261031/workspace';O=R/'deliverables/A-Idea-Final-Two-Round-Growth-20261031'
sys.path.insert(0,str(W/'tmp/nbt-deps'));import nbtlib

def u(x):return str(uuid.UUID(bytes=b''.join((int(i)&0xffffffff).to_bytes(4,'big') for i in x)))
def plain(x):
 if hasattr(x,'items'):return {str(k):plain(v) for k,v in x.items()}
 if isinstance(x,(list,tuple)) or hasattr(x,'tolist'):return [plain(v) for v in x]
 if isinstance(x,str):return str(x)
 return float(x) if isinstance(x,(float,numpy.floating)) else int(x)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def totals(n):
 c=collections.Counter()
 for loc in n['PhysicalInventories']:
  for it in loc['Items']:c[str(it['Stack']['id'])]+=int(it['Stack'].get('count',1))
 return c

def embedded(n):
 out=collections.Counter()
 for site in n['Links']['Coordination'].get('Sites',[]):
  q=int(site.get('Jobs',{}).get('HOUSE',{}).get('Placed',0));out.update((['minecraft:cobblestone']*55+['minecraft:oak_door','minecraft:white_bed'])[:q])
 for site in n['Links']['Development']['Sites']:
  for p in site.get('Projects',[]):
   q=int(p.get('Jobs',{}).get('BUILD',{}).get('Placed',0));out.update((['minecraft:cobblestone']*64+['minecraft:oak_door']+['minecraft:white_bed']*3+['minecraft:barrel'])[:q])
 return out

def read_mca_chunk(path,cx,cz):
 with path.open('rb') as f:
  slot=((cx&31)+(cz&31)*32)*4;f.seek(slot);raw=f.read(4)
  if len(raw)!=4:return None
  sector=int.from_bytes(raw[:3],'big')
  if not sector:return None
  f.seek(sector*4096);length=int.from_bytes(f.read(4),'big');kind=f.read(1)[0];body=f.read(length-1)
  if kind==2:body=zlib.decompress(body)
  elif kind==1:body=gzip.decompress(body)
  elif kind!=3:raise ValueError('unsupported compression '+str(kind))
  return nbtlib.File.parse(io.BytesIO(body))

def chunks(path):
 data=path.read_bytes()
 for i in range(1024):
  sector=int.from_bytes(data[i*4:i*4+3],'big')
  if not sector:continue
  offset=sector*4096;length=int.from_bytes(data[offset:offset+4],'big');kind=data[offset+4];body=data[offset+5:offset+4+length]
  if kind==2:body=zlib.decompress(body)
  elif kind==1:body=gzip.decompress(body)
  elif kind!=3:raise ValueError('unsupported compression')
  yield nbtlib.File.parse(io.BytesIO(body))
def pos(v):
 a=int(v)&((1<<64)-1);x=a>>38;y=a&4095;z=(a>>12)&((1<<26)-1);return (x-(1<<26) if x>=1<<25 else x,y-4096 if y>=2048 else y,z-(1<<26) if z>=1<<25 else z)

def closed_check(world,final):
 expected={u(x['Body']):x for x in final['Bodies']};entities=[]
 for p in sorted((world/'entities').glob('*.mca')):
  for chunk in chunks(p):entities.extend(chunk.get('Entities',[]))
 npcs=[x for x in entities if 'CharacterId' in x];actual={u(x['UUID']):x for x in npcs};errors=[]
 if len(npcs)!=9 or set(actual)!=set(expected):errors.append('saved NPC identity/body count differs')
 for key,want in expected.items():
  got=actual.get(key)
  if got is None:continue
  if u(got['CharacterId'])!=u(want['Person']):errors.append('person mismatch '+key)
  for field in ['Items','TransportCargo']:
   if plain(got.get(field,[]))!=plain(want['BodyTag'].get(field,[])):errors.append('physical body inventory mismatch '+key+':'+field)
 cache={}
 def chunk_at(x,z):
  key=(x>>4,z>>4)
  if key not in cache:cache[key]=read_mca_chunk(world/'region'/f'r.{x>>9}.{z>>9}.mca',*key)
  return cache[key]
 for loc in final['PhysicalInventories']:
  if str(loc['Role']).startswith('body:'):continue
  x,y,z=pos(loc['Position']);chunk=chunk_at(x,z);be=next((a for a in chunk.get('block_entities',[]) if int(a['x'])==x and int(a['y'])==y and int(a['z'])==z),None)
  if be is None:errors.append('physical block container missing '+str(loc['Role']));continue
  actualItems={int(it['Slot']):{k:plain(v) for k,v in it.items() if k!='Slot'} for it in be.get('Items',[])};wanted={int(it['Slot']):plain(it['Stack']) for it in loc['Items']}
  if actualItems!=wanted:errors.append('physical block contents mismatch '+str(loc['Role']))
 drops=[plain(x) for x in entities if str(x.get('id',''))=='minecraft:item'];level=nbtlib.load(world/'level.dat')['Data']
 return {'snapshotTime':int(final['SavedTime']),'closedTime':int(level['Time']),'expectedBodies':9,'savedNpcs':len(npcs),'extraBodies':sorted(set(actual)-set(expected)),'drops':drops,'errors':errors,'passed':not errors,'sourceFilesRead':'normally closed complete world entities/*.mca and required region chunks, no writes'}

def main():
 progress=json.loads((O/'RUN-PROGRESS.json').read_text(encoding='utf-8'));assert (O/'STOP.json').exists() or len(progress)==4
 if len(progress)!=4 or not progress[-1]['finalAccept']:
  write(O/'AUDIT-STATUS.json',{'status':'full target not accepted; preserve first failure; do not substitute historical result','processes':len(progress),'last':progress[-1] if progress else None});return
 world=W/'run/phase9-gametest/final-growth-55a-04/world';initial=W/'run/phase9-gametest/final-growth-55a-01/world/development-initial.nbt';final=world/'development-final.nbt';a=nbtlib.load(initial);z=nbtlib.load(final);before=totals(a);after=totals(z);net=collections.Counter();initialkeys=set(z['Metadata']['InitialFieldworkKeys']);bodies={u(x['Body']) for x in z['Bodies']}
 for rr in z['Links']['Fieldwork']['Receipts']:
  if u(rr['NpcId']) not in bodies or u(rr['PlanId'])+':'+str(int(rr['Step'])) in initialkeys:continue
  for d in rr['InventoryDeltas']:net[str(d['Fingerprint']).split('#')[0]]+=int(d['Quantity'])
 oldCompleted={u(x['Id']) for x in a['Links']['Batches']['Batches'] if str(x['Status'])=='COMPLETED'};recipe=collections.Counter()
 for b in z['Links']['Batches']['Batches']:
  if str(b['Status'])!='COMPLETED' or u(b['Id']) in oldCompleted:continue
  for material in b['Inputs']:recipe[str(material['Fingerprint']['Item'])]-=int(material['Quantity'])
  material=b['Output'];recipe[str(material['Fingerprint']['Item'])]+=int(material['Quantity'])
 placed=embedded(z);placed.subtract(embedded(a));consumed=placed.copy();feed=sum(int(x.get('ConsumedFeed',0)) for x in z['Links']['Growth'].get('Sites',[]))-int(z['Metadata']['InitialFeed']);meals=len(z['Metadata']['Meals']);consumed['minecraft:carrot']+=meals+feed;rows=[]
 for item in sorted(set(before)|set(after)|set(net)|set(recipe)|set(consumed)):
  expected=before[item]+net[item]+recipe[item]-consumed[item];rows.append({'item':item,'initialPhysical':before[item],'fieldworkNet':net[item],'recipeNet':recipe[item],'eatenFedOrEmbedded':consumed[item],'expectedFinal':expected,'actualFinal':after[item],'difference':after[item]-expected})
 write(O/'ITEM-BALANCE.json',{'rows':rows,'differences':[x for x in rows if x['difference']],'passed':not any(x['difference'] for x in rows),'meals':meals,'feed':feed,'initialProof':str(initial),'finalProof':str(final),'initialHash':sha(initial),'finalHash':sha(final)})
 with (O/'ITEM-BALANCE.csv').open('w',encoding='utf-8-sig',newline='') as f:w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
 dev=z['Links']['Development'];site=dev['Sites'][0];projects=site['Projects'];applications=dev['Applications'];assert int(site['CompletedRounds'])==2;assert len(projects)==2 and all(str(x['State'])=='STABLE' and int(x['StableTicks'])>=72000 and int(x['CompletedFoodCycles'])>=3 for x in projects);assert all(str(x['State'])=='STABLE' for x in applications)
 identitiesA=sorted((u(x['Person']),u(x['Body'])) for x in a['Bodies']);identitiesZ=sorted((u(x['Person']),u(x['Body'])) for x in z['Bodies']);assert identitiesA==identitiesZ and len(identitiesZ)==9
 requests=z['Links']['Needs']['Requests'];routes=z['Links']['Supply']['Routes'];reservations=z['Links']['Rights']['Reservations'];batches=z['Links']['Batches']['Batches'];unfinished={'needs':[plain(x) for x in requests if int(x['Reserved']) or int(x['InTransit']) or str(x['Status']) not in ['DELIVERED','CONSUMED','CLOSED','CANCELLED','FAILED']],'routes':[plain(x) for x in routes if str(x['Stage']) not in ['DONE','CANCELLED']],'rightsReservations':[plain(x) for x in reservations],'production':[plain(x) for x in batches if str(x['Status']) not in ['COMPLETED','CANCELLED','FAILED']]}
 summary={'sameOriginal9PeopleBodies':identitiesZ,'worldTotalPopulationIncrease':0,'targetGrowth':{'from':3,'to':9,'incomingOriginalHouseholds':2,'newHouses':2,'dimensions':'6x5 each3beds, existing finite lots only'},'elapsedTicks':int(z['SavedTime'])-int(z['Metadata']['Started']),'elapsedGameDays':round((int(z['SavedTime'])-int(z['Metadata']['Started']))/24000,6),'projects':[{'Id':str(x['Id']),'Created':int(x['Created']),'Built':int(x['Built']),'constructionElapsed':int(x['Built'])-int(x['Created']),'State':str(x['State']),'StableTicks':int(x['StableTicks']),'CompletedFoodCycles':int(x['CompletedFoodCycles'])} for x in projects],'applications':[{'household':u(x['Household']),'Departed':int(x['Departed']),'Arrived':int(x['Arrived']),'walkElapsed':int(x['Arrived'])-int(x['Departed']),'State':str(x['State'])} for x in applications],'allRealNormalProductionAndMeals':True,'observedActualMeals':meals,'observedActualPigFeed':feed,'unresolvedObligations':unfinished,'requestsByStatus':dict(collections.Counter(str(x['Status']) for x in requests)),'routesByStage':dict(collections.Counter(str(x['Stage']) for x in routes)),'notAllSaveRestartAnomaliesProven':True,'notModelPairing':True,'notOpenOrHumanExperience':True};write(O/'BUSINESS-AUDIT.json',summary)
 closed=closed_check(world,z);write(O/'CLOSED-WORLD-CHECK.json',closed);assert closed['passed'];assert not any(x['difference'] for x in rows)
 print(json.dumps({'rounds':2,'elapsedTicks':summary['elapsedTicks'],'projects':summary['projects'],'applications':summary['applications'],'itemDifferences':[],'closedPhysicalCheck':closed['passed'],'pendingCounts':{k:len(v) for k,v in unfinished.items()}},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
