#!/usr/bin/env bash
# Build the actual historical production source, not a rewritten approximation of it.
set -euo pipefail
root="$PWD"
reference="$root/build/reference-legacy-v1"
commit=043ed9d749cf5efff8555d0516ab11b2b3867a89
git fetch --no-tags --depth=1 origin "$commit"
git worktree add --detach "$reference" "$commit"
# Only add the identical TEST observer. Historical production code/data are not modified.
cp src/smoke/java/dev/freshgenerator/smoke/LegacyBlockAudit.java "$reference/src/smoke/java/dev/freshgenerator/smoke/"
cp src/smoke/resources/fabric.mod.json "$reference/src/smoke/resources/fabric.mod.json"
mkdir -p "$reference/run"
printf 'eula=true\n' > "$reference/run/eula.txt"
cat > "$reference/run/server.properties" <<EOF
level-name=reference-other-seed
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
(
  cd "$reference"
  FG_LEGACY_AUDIT=true FG_EXPECTED_SETTINGS=freshgenerator:balanced_v1 FRESHGENERATOR_SMOKE=true \
    timeout --signal=QUIT --kill-after=15s 360s gradle --no-daemon --console=plain runServer
) > build/reports/integration/legacy-reference.log 2>&1 || {
  tail -n 180 build/reports/integration/legacy-reference.log; exit 1;
}
grep 'FRESHGENERATOR_WORLD_PASS\|FRESHGENERATOR_LEGACY_AUDIT' build/reports/integration/legacy-reference.log
cp "$reference/run/freshgenerator-legacy-audit.json" build/reports/integration/legacy-reference.json
cp "$reference/run/freshgenerator-legacy-blocks.txt.gz" build/reports/integration/legacy-reference-blocks.txt.gz
python3 - <<'PY'
import gzip,json,itertools,pathlib
p=pathlib.Path('build/reports/integration')
old=json.loads((p/'legacy-reference.json').read_text())
new=json.loads((p/'legacy-current.json').read_text())
assert old['base_height_columns']==new['base_height_columns']==1280
assert old['base_height_sha256']==new['base_height_sha256'], 'Historical native terrain height changed'
assert old['full_blocks']==new['full_blocks']==491520
changed=[];count=0
chunks=[(0,0),(1,0),(-1,0),(0,1),(0,-1)]
with gzip.open(p/'legacy-reference-blocks.txt.gz','rt') as a,gzip.open(p/'legacy-current-blocks.txt.gz','rt') as b:
    for i,(left,right) in enumerate(itertools.zip_longest(a,b)):
        assert left is not None and right is not None, 'Different block snapshot size'
        if left!=right:
            count+=1
            if len(changed)<32:
                cx,cz=chunks[i//98304];n=i%98304
                changed.append({'x':cx*16+n//6144,'z':cz*16+(n//384)%16,'y':n%384-64,'old':left.strip(),'new':right.strip()})
result={'reference_source_commit':'043ed9d749cf5efff8555d0516ab11b2b3867a89',
        'reference_production_code_unmodified':True,'base_height_columns_equal':1280,
        'full_blocks_compared':491520,'full_block_differences':count,'first_differences':changed,
        'reference':old,'current':new}
(p/'legacy-comparison.json').write_text(json.dumps(result,indent=2)+'\n')
print('FRESHGENERATOR_LEGACY_AB_RESULT '+json.dumps(result))
assert count==0, 'Contemporaneous historical-source A/B block mismatch: review exact differences before publishing'
PY
