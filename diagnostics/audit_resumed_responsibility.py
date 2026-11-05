"""Read final ownership and outstanding duties; no game calls or state writes."""
from pathlib import Path
import json,uuid,hashlib,collections,csv
import audit_resumed_growth as a
W=a.W;O=a.O
def named(k):return str(uuid.UUID(bytes=hashlib.md5(('sagadyssey:v2:'+k).encode()).digest(),version=3))
def write(n,x):(O/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def subject(t):return str(t.get('Type',''))+(':'+a.u(t['Id']) if 'Id' in t else '')
def main():
 result=json.loads((O/'RESULT.json').read_text(encoding='utf-8'));assert result['exitCode']==0 and result['finalAccept']
 z=a.nbtlib.load(W/'run/phase9-gametest/final-growth-55a-05/world/development-final.nbt');now=int(z['SavedTime']);rights=z['Links']['Rights'];contexts={a.u(x['Id']):x for x in rights['Contexts']};claims=collections.defaultdict(list)
 for c in rights['Claims']:claims[(a.u(c['Container']),int(c['Slot']))].append(c)
 rows=[];errors=[];observedIds=set()
 for loc in z['PhysicalInventories']:
  role=str(loc['Role']);cid=a.u(loc['Container']) if 'Container' in loc else named('npc_cargo:'+role.split(':')[1]) if role.startswith('body:') and role.endswith(':transport') else None
  context=contexts.get(cid);observedIds.add(cid)
  if 'BeneficialOwner' in loc and context is not None and loc['BeneficialOwner']!=context['Owner']:errors.append('snapshot/context owner differs '+role)
  for it in loc['Items']:
   slot=int(it['Slot']);item=str(it['Stack']['id']);quantity=int(it['Stack'].get('count',1));explicit=claims.get((cid,slot),[]);claimed=sum(int(c['Quantity']) for c in explicit)
   if claimed>quantity:errors.append('claims exceed real slot '+role+':'+str(slot))
   if any(str(c['Fingerprint']['Item'])!=item for c in explicit):errors.append('claim item mismatch '+role+':'+str(slot))
   if context is None:errors.append('nonempty inventory lacks context '+role)
   for c in explicit:rows.append({'location':role,'container':cid,'slot':slot,'item':item,'quantity':int(c['Quantity']),'owner':subject(c['Owner']),'custodian':subject(c['Custodian']),'basis':'explicit claim','provenance':str(c.get('Provenance',''))})
   if quantity>claimed:rows.append({'location':role,'container':cid,'slot':slot,'item':item,'quantity':quantity-claimed,'owner':subject(context['Owner']) if context else 'UNKNOWN','custodian':subject(context['Custodian']) if context else 'UNKNOWN','basis':'registered container default','provenance':''})
 requests={a.u(x['Id']):x for x in z['Links']['Needs']['Requests']};unfinished=[]
 for r in z['Links']['Supply']['Routes']:
  if str(r['Stage']) in ['DONE','CANCELLED']:continue
  req=requests.get(a.u(r['Need']));rr={'route':a.u(r['Id']),'stage':str(r['Stage']),'quantity':int(r['Quantity']),'principal':subject(r['Principal']),'source':a.u(r['Source']),'destination':a.u(r['Destination']),'carrierPerson':a.u(r['CarrierCharacter']) if 'CarrierCharacter' in r else None,'detail':str(r.get('Detail','')),'retryAt':int(r.get('RetryAt',0)),'pending':a.plain(r.get('Pending',{}))}
  if req is not None:rr.update({'request':a.u(req['Id']),'requestCreated':int(req['CreatedAt']),'requestAge':now-int(req['CreatedAt']),'requestDeadline':int(req['Deadline']),'requestStatus':str(req['Status']),'reserved':int(req['Reserved']),'inTransit':int(req['InTransit']),'delivered':int(req['Delivered']),'detailRequest':str(req.get('Detail',''))})
  unfinished.append(rr)
 waiting=[]
 for q in requests.values():
  if str(q['Status']) in ['CANCELLED','CLOSED','CONSUMED','DELIVERED'] and not int(q['Reserved']) and not int(q['InTransit']):continue
  waiting.append({'id':a.u(q['Id']),'ownerHousehold':a.u(q['Requester']),'item':str(q['Item']['Item']),'status':str(q['Status']),'requested':int(q['Requested']),'delivered':int(q['Delivered']),'reserved':int(q['Reserved']),'inTransit':int(q['InTransit']),'created':int(q['CreatedAt']),'age':now-int(q['CreatedAt']),'deadline':int(q['Deadline']),'detail':str(q.get('Detail','')),'purpose':str(q['Purpose'])})
 receipts=z['Links']['Transfers']['Receipts'];seen=collections.Counter(a.u(x['Id']) for x in receipts);duplicateIds=[k for k,v in seen.items() if v>1]
 if duplicateIds:errors.append('duplicate transfer IDs in saved receipt set')
 homes=z['Links']['Households']['Households'];membership=collections.defaultdict(list)
 for h in homes:
  for m in h['Members']:membership[a.u(m['Character'])].append(a.u(h['Id']))
 if any(len(v)!=1 for v in membership.values()):errors.append('multiple effective households')
 write('OWNERSHIP-AND-DUTIES.json',{'source':'complete final snapshot, confirmed against normally closed world by separate auditor','savedTime':now,'rows':rows,'errors':errors,'passed':not errors,'claimFingerprintComponentHashes':'recorded raw in final proof, this independent reader verifies item and quantities; no replacement Java hash algorithm','claimsOutsideObservedPhysicalContainers':[a.plain(c) for c in rights['Claims'] if a.u(c['Container']) not in observedIds],'reservations':a.plain(rights['Reservations']),'unfinishedRoutes':unfinished,'activeOrUnresolvedNeeds':waiting,'receiptCounts':dict(collections.Counter(str(x['Status']) for x in receipts)),'duplicateReceiptIds':duplicateIds,'membership':dict(membership),'noDemandOrReceiptMutation':True})
 with (O/'OWNERSHIP.csv').open('w',encoding='utf-8-sig',newline='') as f:
  w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
 print(json.dumps({'ownershipRows':len(rows),'errors':errors,'activeNeeds':len(waiting),'unfinishedRoutes':unfinished,'reservationCount':len(rights['Reservations'])},ensure_ascii=False))
 assert not errors
if __name__=='__main__':main()
