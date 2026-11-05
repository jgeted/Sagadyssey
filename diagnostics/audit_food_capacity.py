"""Read-only audit of closed test proofs; inventory, identities, ownership and timings."""
from pathlib import Path
import sys,numpy,json,collections,uuid,hashlib
W=Path(r'C:/Users/jgeted/Desktop/A-Idea/increments/settlement-food-capacity-20261031/workspace');O=W.parents[2]/'deliverables/A-Idea-Settlement-Food-Capacity-20261031'
sys.path.insert(0,str(W/'tmp/nbt-deps'));import nbtlib

def uid(a):return str(uuid.UUID(bytes=b''.join((int(x)&0xffffffff).to_bytes(4,'big') for x in a)))
def plain(v):
 if hasattr(v,'items'):return {str(k):plain(x) for k,x in v.items()}
 if isinstance(v,(list,tuple)) or hasattr(v,'tolist'):return [plain(x) for x in v]
 if isinstance(v,str):return str(v)
 if isinstance(v,(float,numpy.floating)):return float(v)
 return int(v)
def count(items,item):
 return sum(int(x.get('Stack',x).get('count',1)) for x in items if str(x.get('Stack',x).get('id',''))==item)
def inventories(s):
 r={}
 for x in s['WorldStores']:r[str(x['Building'])+':store'+str(x['Index'])]=x['Items']
 for b,x in s['ActualBodies'].items():r['body:'+b+':personal']=x['Items'];r['body:'+b+':transport']=x['TransportCargo']['Items']
 r['futurePantry']=s.get('FuturePantryItems',[])
 return r
def totals(s):
 r=collections.Counter()
 for rows in inventories(s).values():
  for x in rows:
   a=x.get('Stack',x);r[str(a['id'])]+=int(a.get('count',1))
 return dict(r)
def receipt(s,rid):return next(x for x in s['Transfers']['Receipts'] if uid(x['Id'])==rid)
def context(s,cid):return next(x for x in s['Rights']['Contexts'] if uid(x['Id'])==cid)
def owner_slots(s,cid,rows):
 ctx=context(s,cid);out=[]
 for x in rows:
  a=x.get('Stack',x);slot=int(x['Slot']);claim=next((c for c in s['Rights']['Claims'] if uid(c['Container'])==cid and int(c['Slot'])==slot and str(c['Fingerprint']['Item'])==str(a['id'])),None)
  if claim is not None:assert int(claim['Quantity'])==int(a.get('count',1))
  owner=(claim or ctx)['Owner'];out.append({'slot':slot,'item':str(a['id']),'quantity':int(a.get('count',1)),'ownerType':str(owner['Type']),'ownerId':uid(owner['Id']),'explicitClaim':claim is not None})
 return out
def audit_case(cid):
 path=W/f'run/phase9-gametest/food-cap-{cid.lower()}-20261031-01/world/foodcap-{cid}-proof.nbt';n=nbtlib.load(path);a=n['Initial'];z=n['Final'];initial=totals(a);final=totals(z)
 ids0=sorted((uid(b['CharacterId']),uid(b['UUID'])) for b in a['ActualBodies'].values());ids1=sorted((uid(b['CharacterId']),uid(b['UUID'])) for b in z['ActualBodies'].values());assert ids0==ids1 and len(ids0)==9
 # All material items are counted once: original stores, personal bags, physical transport cargo, future pantry. HandItems are presentation aliases and not added.
 assert initial==final,(cid,initial,final)
 field=[x for x in z['Fieldwork']['Receipts'] if int(x['CommittedAt'])>int(a['At'])];assert not field
 meals=[x for x in z['Life']['Needs'] if int(x['LastAte'])>int(a['At'])];assert not meals
 r={'case':cid,'proof':str(path),'proofSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'result':str(n['Result']),'sameOriginalPersonBodyPairs':ids1,'initialMaterialTotals':initial,'finalMaterialTotals':final,'newFieldworkReceipts':0,'newMeals':0,'conservation':'exact_no_unexplained_material_difference','snapshotLocations':{k:{x:{item:count(v,item) for item in ['minecraft:carrot','minecraft:cobblestone']} for x,v in inventories(n[k]).items()} for k in ['Initial','FirstRejection','CapacityRestored','ActualPickup','ActualDelivery'] if k in n}}
 if str(n['Result'])=='FAIL':
  f=z['Development']['Sites'][0]['Projects'][0]['Jobs']['FOOD'];last=receipt(z,uid(f['LastTransfer']));assert str(last['Status'])=='SUCCESS' and int(f['Quantity'])==8 and bool(f['Cargo'])
  r['firstFailure']={'reason':str(n['Reason']),'tick':int(z['At']),'frozenExpectedQuantity':9,'actualPickupQuantity':8,'elapsedAfterCapacityRestored':int(z['At'])-int(n['CapacityRestored']['At']),'foodJob':plain(f),'actualPickupReceipt':plain(last),'allNewReceipts':[plain(x) for x in z['Transfers']['Receipts'] if int(x['CompletedAt'])>int(a['At'])],'classification':'frozen N07 q9 expectation not met: independent lawful route took1 after initial farmer gift1; q8 actual pickup is not evidence for changed-q9 request or delivery. No repeat or changed criterion.'}
  return r
 if cid=='N04':
  f0=a['Development']['Sites'][0]['Projects'][0]['Jobs']['FOOD'];fz=z['Development']['Sites'][0]['Projects'][0]['Jobs']['FOOD'];assert bool(fz['Review']) and not bool(fz.get('Cargo',0)) and int(fz.get('Cycle',0))==0;r['faultInjection']='one failed receipt removed in new negative fixture; no spontaneous runtime fault claimed';r['recovery']='not passed: Review safety only';return r
 p=n['ActualPickup'];d=n['ActualDelivery'];pickupjob=p['Development']['Sites'][0]['Projects'][0]['Jobs']['FOOD'];dropjob=d['Development']['Sites'][0]['Projects'][0]['Jobs']['FOOD'];person=uid(pickupjob['Person']);body=uid(pickupjob['Body']);failed=receipt(d,uid(n['FailedActualId']));pick=receipt(d,uid(pickupjob['LastTransfer']));drop=receipt(d,uid(dropjob['LastTransfer']));pl=pick['RequestIdentity']['Legs'][0];dl=drop['RequestIdentity']['Legs'][0];src=uid(pl['Source']);bag=uid(pl['Destination']);pan=uid(dl['Destination']);assert uid(dl['Source'])==bag
 assert str(failed['Status'])=='CAPACITY_EXCEEDED' and bool(failed['Lawful']) and not failed['Revisions'];assert all(str(x['Status'])=='SUCCESS' and bool(x['Lawful']) for x in [pick,drop]);assert int(pl['Quantity'])==int(dl['Quantity'])==9
 assert int(n['PickedAt'])-int(n['RestoredAt'])<=600 and int(n['DeliveryElapsed'])<=2400
 raw0=a['WorldStores'][0]['Items'];raw1=d['WorldStores'][0]['Items'];sourceNet=0;movements=[]
 for rr in d['Transfers']['Receipts']:
  if int(rr['CompletedAt'])<=int(a['At']) or str(rr['Status'])!='SUCCESS':continue
  for leg in rr.get('RequestIdentity',{}).get('Legs',[]):
   if str(leg['Item']['Item'])!='minecraft:carrot':continue
   delta=int(leg['Quantity'])*((uid(leg['Destination'])==src)-(uid(leg['Source'])==src));sourceNet+=delta
   if delta:movements.append({'actualId':uid(rr['Id']),'at':int(rr['CompletedAt']),'sourceDelta':delta,'identity':plain(rr['RequestIdentity'])})
 assert count(raw1,'minecraft:carrot')==count(raw0,'minecraft:carrot')+sourceNet
 sourceOwners0=owner_slots(a,src,raw0);sourceOwners1=owner_slots(d,src,raw1);assert {x['ownerId'] for x in sourceOwners0}=={x['ownerId'] for x in sourceOwners1}
 bagOwners=owner_slots(p,bag,p['ActualBodies'][body]['Items']);targetOwners=owner_slots(d,pan,d['FuturePantryItems']);assert all(x['ownerId']==person for x in bagOwners if x['item']=='minecraft:carrot');assert all(x['ownerType']=='HOUSEHOLD' and x['ownerId']==uid(d['FuturePantryHousehold']) for x in targetOwners)
 r.update({'restoredAt':int(n['RestoredAt']),'pickedAt':int(n['PickedAt']),'elapsedPickupUpperBoundQualified':int(n['PickedAt'])-int(n['RestoredAt']),'deliveryElapsedSeparateClock':int(n['DeliveryElapsed']),'quantity':9,'sourceInventoryInitial':count(raw0,'minecraft:carrot'),'sourceInventoryFinal':count(raw1,'minecraft:carrot'),'sourceNetAllLawfulTransfers':sourceNet,'sourceMovementsIncludingIndependentOldRoute':movements,'failedAttempt':plain(failed),'pickup':plain(pick),'delivery':plain(drop),'sourceOwnershipBefore':sourceOwners0,'sourceOwnershipAfter':sourceOwners1,'pickupBagOwnership':bagOwners,'deliveryPantryOwnership':targetOwners,'finalFoodJob':plain(dropjob),'accountingNotes':'Reservation and pending promises not added as physical stock; all new crop/meal effects checked absent; separate existing normal routes included in source net, not attributed to FOOD twice.'})
 return r

def main():
 result=[audit_case(c) for c in ['N01','N02','N03','N04','N07']];(O/'ACCOUNTING.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print([(r['case'],r.get('elapsedPickupUpperBoundQualified'),r.get('deliveryElapsedSeparateClock'),r['conservation']) for r in result])
if __name__=='__main__':main()
