"""Isolated preservation and declared case preparation. Never writes source worlds."""
import argparse, datetime, hashlib, json, pathlib, shutil, sys
ROOT=pathlib.Path(r'C:/Users/jgeted/Desktop/A-Idea')
SOURCE=ROOT/'increments/settlement-housing-v8-20261029/workspace'
DESIGN=ROOT/'deliverables/A-Idea-Settlement-Decision-Design-20261030/supplement-v1.1-20261030'
WORK=ROOT/'increments/settlement-rule-diagnostics-v11-20261030/workspace'
OUT=ROOT/'deliverables/A-Idea-Settlement-Rule-Diagnostics-v11-20261030'
def sha(p):
 h=hashlib.sha256()
 with pathlib.Path(p).open('rb') as f:
  for x in iter(lambda:f.read(4*1024*1024),b''):h.update(x)
 return h.hexdigest()
def write(p,d):
 p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--baseline',action='store_true');ap.add_argument('--case');a=ap.parse_args()
 if a.baseline:
  if OUT.exists():raise SystemExit('Refuse existing evidence directory')
  audit=json.loads((DESIGN/'READ-ONLY-AUDIT-v1.1.json').read_text(encoding='utf-8'));files=audit['sourceBeforeHashes']
  OUT.mkdir(parents=True)
  for rel,z in files.items():
   src=SOURCE/rel;dst=WORK/rel
   if sha(src)!=z:raise SystemExit('Baseline changed: '+rel)
   if dst.exists():raise SystemExit('Refuse overwriting '+str(dst))
   dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
   if sha(dst)!=z:raise SystemExit('Copy mismatch '+rel)
  # Existing generated caches are reusable inputs, copied independently; never linked.
  for rel in ['.gradle','build']:
   src=SOURCE/rel;dst=WORK/rel
   if src.exists() and not dst.exists():shutil.copytree(src,dst)
  write(OUT/'BASELINE.json',{'startedUtc':'2026-10-30T12:51:23Z','deadlineUtc':'2026-10-30T14:21:23Z','source':str(SOURCE),'workspace':str(WORK),'candidateJarSha256':audit['v8CandidateJarSha256'],'necessaryInputs':files,'originalDesignFiles':audit['originalDesignFiles'],'oldEvidenceUntouched':True,'copiedGeneratedCaches':['.gradle','build'],'copyVerified':True})
  print('Preserved',len(files),'necessary inputs')
 elif a.case:
  spec=json.loads((WORK/'spec/first-batch-v11.json').read_text(encoding='utf-8'));case=next(x for x in spec['nativeCases'] if x['id']==a.case)
  target=WORK/'run/phase9-gametest'/case['runLabel'];target.mkdir(parents=True,exist_ok=False)
  if case['profile'].startswith('P2'):
   origin=pathlib.Path(spec['profiles']['P2']['closedCheckpoint']);dest=target/'world'
   shutil.copytree(origin,dest)
   audit=json.loads((DESIGN/'P2-checkpoint-exact-state.json').read_text(encoding='utf-8'))
   for rel,z in audit['fullSourceWorldFileHashes'].items():
    if sha(dest/rel)!=z:raise SystemExit('Checkpoint copy mismatch '+rel)
  write(OUT/case['id']/'PREPARE.json',{'case':case,'target':str(target),'fullWorldCopy':case['profile'].startswith('P2'),'sourceWorldNotEdited':True})
  print(target)
 else:ap.error('choose --baseline or --case')
if __name__=='__main__':main()
