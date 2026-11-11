#!/usr/bin/env bash
# Ephemeral CI worlds only. Never runs against a player's save folder.
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
max-tick-time=180000
spawn-protection=0
EOF
  rm -f run/freshgenerator-smoke.json
  FG_EXPECTED_SETTINGS="$preset" FRESHGENERATOR_SMOKE=true timeout --signal=QUIT --kill-after=15s 300s \
    gradle --no-daemon --console=plain runServer > "build/reports/integration/$label.log" 2>&1 || {
      tail -n 180 "build/reports/integration/$label.log"; return 1;
    }
  grep 'FRESHGENERATOR_WORLD_PASS' "build/reports/integration/$label.log"
  cp run/freshgenerator-smoke.json "build/reports/integration/$label.json"
}
run_world balanced-new smoke-balanced 42 freshgenerator:balanced_v1
run_world balanced-reload smoke-balanced 42 freshgenerator:balanced_v1
run_world other-seed smoke-other 1337 freshgenerator:balanced_v1
run_world highlands-new smoke-highlands 42 freshgenerator:highlands_v1
python3 - <<'PY'
import json,pathlib
p=pathlib.Path('build/reports/integration')
a,b,c,d=[json.loads((p/(x+'.json')).read_text()) for x in ['balanced-new','balanced-reload','other-seed','highlands-new']]
assert a['climate_sha256']==b['climate_sha256'], 'Climate changed on reload'
assert a['block_sha256']==b['block_sha256'], 'Saved terrain changed on reload'
assert a['climate_sha256']!=c['climate_sha256'], 'World seed did not affect generation'
assert a['climate_sha256']!=d['climate_sha256'], 'Different profiles produced identical climate layout'
assert all(x['full_chunks_checked']==5 and x['core_vs_native_max_error']<=.01 for x in [a,b,c,d])
print('FRESHGENERATOR_INTEGRATION_PASS: two profiles, two seeds, full chunk generation and save reload')
PY
