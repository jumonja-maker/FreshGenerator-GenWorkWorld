#!/usr/bin/env bash
# Only temporary CI worlds; never a player's save folder.
set -euo pipefail
mkdir -p run build/reports/integration
printf 'eula=true\n' > run/eula.txt
run_world() {
  local label="$1" world="$2" seed="$3" preset="$4"
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
  rm -f run/freshgenerator-smoke.json run/freshgenerator-heightmap.csv
  FG_EXPECTED_SETTINGS="$preset" FRESHGENERATOR_SMOKE=true timeout --signal=QUIT --kill-after=15s 360s \
    gradle --no-daemon --console=plain runServer > "build/reports/integration/$label.log" 2>&1 || {
      tail -n 180 "build/reports/integration/$label.log"; return 1;
    }
  grep 'FRESHGENERATOR_WORLD_PASS' "build/reports/integration/$label.log"
  cp run/freshgenerator-smoke.json "build/reports/integration/$label.json"
  if [ -f run/freshgenerator-heightmap.csv ]; then
    cp run/freshgenerator-heightmap.csv "build/reports/integration/$label.csv"
  fi
}
run_world balanced-new smoke-balanced 42 freshgenerator:balanced_v1
run_world balanced-reload smoke-balanced 42 freshgenerator:balanced_v1
run_world other-seed smoke-other 1337 freshgenerator:balanced_v1
run_world highlands-new smoke-highlands 42 freshgenerator:highlands_v1
run_world natural-new smoke-natural 42 freshgenerator:balanced_v2
run_world natural-other-seed smoke-natural-other 1337 freshgenerator:balanced_v2
run_world alpine-new smoke-alpine 42 freshgenerator:highlands_v2
run_world alpine-reload smoke-alpine 42 freshgenerator:highlands_v2
python3 - <<'PY'
import json,pathlib
p=pathlib.Path('build/reports/integration')
read=lambda x:json.loads((p/(x+'.json')).read_text())
a,b,c,d=[read(x) for x in ['balanced-new','balanced-reload','other-seed','highlands-new']]
assert a['climate_sha256']==b['climate_sha256'] and a['block_sha256']==b['block_sha256']
assert a['climate_sha256']!=c['climate_sha256'] and a['climate_sha256']!=d['climate_sha256']
old=json.loads(pathlib.Path('dist/validation-0.1.0-alpha.1.json').read_text())['world_scenarios']
for name in ['balanced-new','balanced-reload','other-seed','highlands-new']:
    current=read(name)
    assert current['block_sha256']==old[name]['block_sha256'], 'V1 world changed: '+name
    assert current['climate_sha256']==old[name]['climate_sha256'], 'V1 climate changed: '+name
n,o,h,r=[read(x) for x in ['natural-new','natural-other-seed','alpine-new','alpine-reload']]
assert h['block_sha256']==r['block_sha256'], 'V2 target chunks changed on reload'
assert h['climate_sha256']==r['climate_sha256']
assert n['climate_sha256']!=o['climate_sha256'], 'V2 world seed ignored'
for x in [n,o,h,r]:
    assert x['core_vs_native_max_error']<=.02
    assert x['unchanged_climate_axis_samples']==2560
    assert x['full_chunks_checked']>=9 and x['distinct_base_heights']>=3
    assert x['sampled_peak_uplift']>=4
print('FRESHGENERATOR_INTEGRATION_PASS: v1 regression, v2 native mountain/boundary chunks, two seeds and restart')
PY
