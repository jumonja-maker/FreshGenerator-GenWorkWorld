#!/usr/bin/env bash
# One seed and its vanilla control; separate CI jobs preserve all raw observations.
set -euo pipefail
cd "$(dirname "$0")/.."
seed=${1:?seed required};[[ "$seed" = 42 || "$seed" = 1337 || "$seed" = -20261111 ]]
mkdir -p run build/reports/technical
printf 'eula=true\n'>run/eula.txt
for mode in fg vanilla;do
 label="$mode-$seed";preset=minecraft:normal;expected=minecraft:overworld
 if [ "$mode" = fg ];then preset=freshgenerator:world;expected=freshgenerator:highlands_v2;fi
 cat > run/server.properties <<PROPS
level-name=technical-$label
level-seed=$seed
level-type=$preset
generate-structures=true
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
max-tick-time=600000
spawn-protection=0
PROPS
 rm -f run/freshgenerator-audit.json run/freshgenerator-envelope.json
 if ! FRESHGENERATOR_AUDIT=true FG_AUDIT_CASE="$label" FG_AUDIT_SETTINGS="$expected" timeout --signal=QUIT --kill-after=15s 720s \
  gradle --no-daemon --console=plain runServer > "build/reports/technical/$label.log" 2>&1;then
  tail -n 120 "build/reports/technical/$label.log";exit 1
 fi
 grep -q 'FRESHGENERATOR_AUDIT_PASS' "build/reports/technical/$label.log"
 cp run/freshgenerator-audit.json "build/reports/technical/$label.json"
 cp run/server.properties "build/reports/technical/$label.properties"
 if [ "$mode" = fg ];then
  grep -q 'FRESHGENERATOR_ENVELOPE_PASS' "build/reports/technical/$label.log"
  cp run/freshgenerator-envelope.json "build/reports/technical/$label-envelope.json"
 fi
done
python3 - "$seed" <<'PY'
import hashlib,json,os,pathlib,sys
seed=int(sys.argv[1]);p=pathlib.Path('build/reports/technical')
for mode in ['fg','vanilla']:
 r=json.loads((p/f'{mode}-{seed}.json').read_text());assert r['completed'] is True and r['seed']==seed
files={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in p.iterdir() if f.is_file()}
(p/f'meta-{seed}.json').write_text(json.dumps({'source_commit':os.environ['GITHUB_SHA'],'seed':seed,'actual_server_runs':2,'files_sha256':files},indent=2)+'\n')
PY
