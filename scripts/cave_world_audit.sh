#!/usr/bin/env bash
# Same observer on immutable reference and candidate, fresh worlds and fixed coordinates.
set -euo pipefail
cd "$(dirname "$0")/.."
seed=${1:-42};scope=${2:-fresh};[[ "$seed" =~ ^-?[0-9]+$ ]];[[ "$scope" = fresh || "$scope" = normal ]]
root=$PWD;reference=319c9060109ff29e894a35e45d92f604f845d2c5;candidate=$(git rev-parse HEAD)
report="$root/build/reports/caves/$scope-$seed";mkdir -p "$report"
scratch=$(mktemp -d "$root/build/cave-ab.XXXXXX")
cleanup(){ for mode in reference candidate;do git worktree remove --force "$scratch/$mode" >/dev/null 2>&1||true;done;rm -rf -- "$scratch"; }
trap cleanup EXIT
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=1 -Dmax.bg.threads=1 -Dfreshgenerator.caveAudit=true"
for mode in reference candidate;do
 source=$reference;[ "$mode" = candidate ]&&source=$candidate
 tree="$scratch/$mode";git worktree add --detach "$tree" "$source"
 for observer in CaveWorldAudit CaveTerrainSnapshot;do
  cp "$root/src/audit/java/dev/freshgenerator/audit/$observer.java" "$tree/src/audit/java/dev/freshgenerator/audit/$observer.java"
 done
 python3 - "$tree/src/audit/resources/fabric.mod.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);j=json.loads(p.read_text());j['entrypoints']['main']=['dev.freshgenerator.audit.CaveWorldAudit'];p.write_text(json.dumps(j,indent=2)+'\n')
PY
 run="$tree/cave-run";mkdir -p "$run";printf 'eula=true\n'>"$run/eula.txt"
 preset=freshgenerator:world;[ "$scope" = normal ]&&preset=minecraft:normal
 cat > "$run/server.properties" <<EOF
level-name=world
level-seed=$seed
level-type=$preset
generate-structures=false
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
max-tick-time=600000
spawn-protection=0
EOF
 passes=1;[ "$mode" = candidate ]&&passes=2
 for ((i=1;i<=passes;i++));do
  label=$mode;[ "$i" = 2 ]&&label=candidate-reload
  log="$report/$label.log"
  if ! (cd "$tree";FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_CAVE_AUDIT=true FG_CAVE_MODE="$mode" FG_CAVE_PASS="$label" FG_CAVE_SCOPE="$scope" FG_CAVE_SOURCE="$source" FG_CAVE_COORDINATES="$report/coordinates.json" \
    timeout --signal=QUIT --kill-after=15s 1200s gradle --no-daemon --console=plain runServer > "$log" 2>&1);then tail -n 160 "$log";exit 1;fi
  grep 'CAVE_TERRAIN_STAGE\|CAVE_AUDIT_STAGE\|FRESHGENERATOR_CAVE_WORLD_PASS' "$log"
  cp "$run/cave-audit.json" "$report/$label.json"
 done
 git -C "$tree" diff --exit-code -- src/main src/core src/tool
done
python3 scripts/compare_cave_audit.py "$report"
