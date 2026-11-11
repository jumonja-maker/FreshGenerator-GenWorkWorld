#!/usr/bin/env bash
# Same historical production source twice: distinguish regressions from control nondeterminism.
set -euo pipefail
root="$PWD"; reference="$root/build/reference-legacy-v1"
commit=043ed9d749cf5efff8555d0516ab11b2b3867a89
git fetch --no-tags --depth=1 origin "$commit"
git worktree add --detach "$reference" "$commit"
cp src/smoke/java/dev/freshgenerator/smoke/LegacyBlockAudit.java "$reference/src/smoke/java/dev/freshgenerator/smoke/"
cp src/smoke/resources/fabric.mod.json "$reference/src/smoke/resources/fabric.mod.json"
mkdir -p "$reference/run";printf 'eula=true\n' > "$reference/run/eula.txt"
for repetition in reference reference-repeat; do
 cat > "$reference/run/server.properties" <<EOF
level-name=$repetition-other-seed
level-seed=1337
level-type=freshgenerator:balanced_v1
online-mode=false
server-ip=127.0.0.1
server-port=25579
view-distance=2
simulation-distance=2
max-tick-time=240000
spawn-protection=0
EOF
 (cd "$reference"; FG_LEGACY_AUDIT=true FG_EXPECTED_SETTINGS=freshgenerator:balanced_v1 FRESHGENERATOR_SMOKE=true \
    timeout --signal=QUIT --kill-after=15s 360s gradle --no-daemon --console=plain runServer) > "build/reports/integration/legacy-$repetition.log" 2>&1 || {
      tail -n 180 "build/reports/integration/legacy-$repetition.log";exit 1;
    }
 grep 'FRESHGENERATOR_WORLD_PASS\|FRESHGENERATOR_LEGACY_AUDIT' "build/reports/integration/legacy-$repetition.log"
 cp "$reference/run/freshgenerator-legacy-audit.json" "build/reports/integration/legacy-$repetition.json"
 cp "$reference/run/freshgenerator-legacy-blocks.txt.gz" "build/reports/integration/legacy-$repetition-blocks.txt.gz"
done
python3 - <<'PY'
import gzip,json,itertools,pathlib,os
p=pathlib.Path('build/reports/integration');read=lambda name:json.loads((p/f'legacy-{name}.json').read_text())
old,again,new=read('reference'),read('reference-repeat'),read('current')
assert old['base_height_sha256']==again['base_height_sha256']==new['base_height_sha256']
assert old['full_blocks']==again['full_blocks']==new['full_blocks']==491520
chunks=[(0,0),(1,0),(-1,0),(0,1),(0,-1)]
def compare(a,b):
 count=0;changed=[]
 with gzip.open(p/f'legacy-{a}-blocks.txt.gz','rt') as left,gzip.open(p/f'legacy-{b}-blocks.txt.gz','rt') as right:
  for i,(x,y) in enumerate(itertools.zip_longest(left,right)):
   assert x is not None and y is not None
   if x!=y:
    count+=1
    if len(changed)<32:
     cx,cz=chunks[i//98304];n=i%98304
     changed.append({'x':cx*16+n//6144,'z':cz*16+(n//384)%16,'y':n%384-64,'old':x.strip(),'new':y.strip()})
 return count,changed
aa,aa_examples=compare('reference','reference-repeat');ab,examples=compare('reference','current')
result={'reference_source_commit':'043ed9d749cf5efff8555d0516ab11b2b3867a89','reference_production_code_unmodified':True,
 'test_process_options':os.environ.get('JAVA_TOOL_OPTIONS',''),'base_height_columns_equal':1280,'full_blocks_compared':491520,
 'reference_AA_differences':aa,'reference_AA_examples':aa_examples,'full_block_differences':ab,'first_differences':examples,'reference':old,'reference_repeat':again,'current':new}
(p/'legacy-comparison.json').write_text(json.dumps(result,indent=2)+'\n');print('FRESHGENERATOR_LEGACY_AB_RESULT '+json.dumps(result))
assert aa==0,'Historical control itself is not repeatable; do not claim an A/B regression result'
assert ab==0,'Controlled historical-source A/B mismatch remains'
PY
