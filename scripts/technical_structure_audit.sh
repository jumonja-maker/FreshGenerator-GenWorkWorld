#!/usr/bin/env bash
# One seed and its vanilla control; separate CI jobs preserve all raw observations.
set -euo pipefail
cd "$(dirname "$0")/.."
seed=${1:?seed required};[[ "$seed" =~ ^-?[0-9]+$ ]]
source_commit=${GITHUB_SHA:-$(git rev-parse HEAD)}
mkdir -p build/reports/technical
report=$(mktemp -d "build/reports/technical/structures-$seed.XXXXXX")
printf 'TECHNICAL_STRUCTURE_REPORT_DIR=%s\n' "$report"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
 printf 'report_dir=%s\n' "$report" >> "$GITHUB_OUTPUT"
fi
scratch=$(mktemp -d "$PWD/build/structure-worlds-$seed.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
for mode in fg vanilla;do
 label="$mode-$seed";preset=minecraft:normal;expected=minecraft:overworld
 if [ "$mode" = fg ];then preset=freshgenerator:world;expected=freshgenerator:highlands_v2;fi
 run="$scratch/$mode"
 mkdir -p "$run"
 printf 'eula=true\n' > "$run/eula.txt"
 cat > "$run/server.properties" <<PROPS
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
 cp "$run/server.properties" "$report/$label.properties"
 status=0
 FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_AUDIT_CASE="$label" FG_AUDIT_SETTINGS="$expected" \
  timeout --signal=QUIT --kill-after=15s 720s gradle --no-daemon --console=plain runServer \
  > "$report/$label.log" 2>&1 || status=$?
 if [ -f "$run/freshgenerator-audit.json" ]; then
  cp "$run/freshgenerator-audit.json" "$report/$label.json"
 fi
 if [ "$mode" = fg ] && [ -f "$run/freshgenerator-envelope.json" ]; then
  cp "$run/freshgenerator-envelope.json" "$report/$label-envelope.json"
 fi
 if [ "$status" -ne 0 ] || [ ! -f "$report/$label.json" ] || ! grep -q 'FRESHGENERATOR_AUDIT_PASS' "$report/$label.log"; then
  tail -n 120 "$report/$label.log";exit 1
 fi
 if [ "$mode" = fg ]; then
  test -f "$report/$label-envelope.json"
  grep -q 'FRESHGENERATOR_ENVELOPE_PASS' "$report/$label.log"
 fi
done
python3 - "$seed" "$report" "$source_commit" <<'PY'
import hashlib
import json
import pathlib
import sys

seed = int(sys.argv[1])
report = pathlib.Path(sys.argv[2])
names = [f'fg-{seed}-envelope.json']
for mode in ('fg', 'vanilla'):
    label = f'{mode}-{seed}'
    data = json.loads((report / f'{label}.json').read_text())
    if data['completed'] is not True or data['seed'] != seed or data['case'] != label:
        raise ValueError(f'Unexpected structure observation: {label}')
    names.extend(f'{label}.{suffix}' for suffix in ('json', 'log', 'properties'))
files = {name: hashlib.sha256((report / name).read_bytes()).hexdigest() for name in names}
metadata = {'source_commit': sys.argv[3], 'seed': seed, 'actual_server_runs': 2, 'files_sha256': files}
(report / f'meta-{seed}.json').write_text(json.dumps(metadata, indent=2) + '\n')
PY
