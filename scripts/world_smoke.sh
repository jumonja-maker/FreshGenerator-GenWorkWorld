#!/usr/bin/env bash
# Only disposable CI worlds. Limit CPU visibility for reproducible legacy decoration ordering.
# This is a test process option, NOT a runtime limit shipped with the mod.
set -euo pipefail
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=1 -Dmax.bg.threads=1"
mkdir -p run build/reports/integration
printf 'eula=true\n' > run/eula.txt
run_world() {
  local label="$1" world="$2" seed="$3" preset="$4" expected="${5:-$4}" audit=false
  if [ "$label" = other-seed ]; then audit=true; fi
  cat > run/server.properties <<EOF
level-name=$world
level-seed=$seed
level-type=$preset
online-mode=false
server-ip=127.0.0.1
server-port=25579
view-distance=2
simulation-distance=2
max-tick-time=240000
spawn-protection=0
EOF
  rm -f run/freshgenerator-smoke.json run/freshgenerator-heightmap.csv run/freshgenerator-legacy-audit.json
  FG_LEGACY_AUDIT="$audit" FG_EXPECTED_SETTINGS="$expected" FRESHGENERATOR_SMOKE=true timeout --signal=QUIT --kill-after=15s 360s \
    gradle --no-daemon --console=plain runServer > "build/reports/integration/$label.log" 2>&1 || {
      tail -n 180 "build/reports/integration/$label.log"; return 1;
    }
  grep 'FRESHGENERATOR_WORLD_PASS' "build/reports/integration/$label.log"
  cp run/freshgenerator-smoke.json "build/reports/integration/$label.json"
  cp run/server.properties "build/reports/integration/$label.properties"
  if [ -f run/freshgenerator-heightmap.csv ]; then cp run/freshgenerator-heightmap.csv "build/reports/integration/$label.csv"; fi
  if [ "$audit" = true ]; then
    cp run/freshgenerator-legacy-audit.json build/reports/integration/legacy-current.json
    cp run/freshgenerator-legacy-blocks.txt.gz build/reports/integration/legacy-current-blocks.txt.gz
  fi
}
run_world other-seed smoke-other 1337 freshgenerator:balanced_v1
bash scripts/legacy_audit.sh
run_world balanced-new smoke-balanced 42 freshgenerator:balanced_v1
run_world balanced-reload smoke-balanced 42 freshgenerator:balanced_v1
run_world highlands-new smoke-highlands 42 freshgenerator:highlands_v1
run_world natural-new smoke-natural 42 freshgenerator:balanced_v2
run_world natural-other-seed smoke-natural-other 1337 freshgenerator:balanced_v2
run_world world-new smoke-world 42 freshgenerator:world freshgenerator:highlands_v2
run_world world-reload smoke-world 42 freshgenerator:world freshgenerator:highlands_v2
python3 - <<'PY'
import json,pathlib
p=pathlib.Path('build/reports/integration');read=lambda x:json.loads((p/(x+'.json')).read_text())
a,b,c,d=[read(x) for x in ['balanced-new','balanced-reload','other-seed','highlands-new']]
assert a['climate_sha256']==b['climate_sha256'] and a['block_sha256']==b['block_sha256']
assert a['climate_sha256']!=c['climate_sha256'] and a['climate_sha256']!=d['climate_sha256']
old=json.loads(pathlib.Path('dist/validation-0.1.0-alpha.1.json').read_text())['world_scenarios'];historical={}
for name in ['balanced-new','balanced-reload','other-seed','highlands-new']:
 current=read(name);assert current['climate_sha256']==old[name]['climate_sha256'],'V1 climate changed: '+name
 historical[name]={'block_hash_matches':current['block_sha256']==old[name]['block_sha256'],'reference_block_sha256':old[name]['block_sha256'],'current_block_sha256':current['block_sha256']}
(p/'historical-v1-comparison.json').write_text(json.dumps(historical,indent=2)+'\n')
print('HISTORICAL_V1_FULL_BLOCK_COMPARISON '+json.dumps(historical))
n,o,h,r=[read(x) for x in ['natural-new','natural-other-seed','world-new','world-reload']]
assert h['block_sha256']==r['block_sha256'],'Single world target chunks changed on reload'
assert h['climate_sha256']==r['climate_sha256'] and n['climate_sha256']!=o['climate_sha256']
for label in ['world-new','world-reload']:
 properties=(p/(label+'.properties')).read_text().replace(chr(92)+':', ':')
 assert 'level-type=freshgenerator:world\n' in properties
 assert read(label)['settings']=='freshgenerator:highlands_v2'
for x in [n,o,h,r]:
 assert x['core_vs_native_max_error']<=.02 and x['unchanged_climate_axis_samples']==2560
 assert x['full_chunks_checked']>=9 and x['distinct_base_heights']>=3 and x['sampled_peak_uplift']>=4
print('FRESHGENERATOR_INTEGRATION_PASS: controlled legacy A/A/B and single public world create/reopen')
PY
