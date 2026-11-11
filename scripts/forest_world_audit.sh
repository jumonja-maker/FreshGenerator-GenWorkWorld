#!/usr/bin/env bash
# Same observer, separate unmodified production trees. Limits are for isolated test processes only.
set -euo pipefail
cd "$(dirname "$0")/.."
seed=${1:-42};scope=${2:-fresh};[[ "$seed" =~ ^-?[0-9]+$ ]]
root=$PWD;reference=75f7c44d515257edfd821b0f607f02af39e73ade;candidate=$(git rev-parse HEAD)
report="$root/build/reports/forest/$scope-$seed";mkdir -p "$report"
scratch=$(mktemp -d "$root/build/forest-ab.XXXXXX")
cleanup(){ for mode in reference candidate;do git worktree remove --force "$scratch/$mode" >/dev/null 2>&1||true;done;rm -rf -- "$scratch"; }
trap cleanup EXIT
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=1 -Dmax.bg.threads=1 -Dfreshgenerator.forestAudit=true"
for mode in reference candidate;do
 source=$reference;[ "$mode" = candidate ]&&source=$candidate
 tree="$scratch/$mode";git worktree add --detach "$tree" "$source"
 cp "$root/src/audit/java/dev/freshgenerator/audit/ForestWorldAudit.java" "$tree/src/audit/java/dev/freshgenerator/audit/ForestWorldAudit.java"
 python3 - "$tree/src/audit/resources/fabric.mod.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);j=json.loads(p.read_text());j['entrypoints']['main']=['dev.freshgenerator.audit.ForestWorldAudit'];p.write_text(json.dumps(j,indent=2)+'\n')
PY
 run="$tree/forest-run";mkdir -p "$run";printf 'eula=true\n'>"$run/eula.txt"
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
  if ! (cd "$tree";FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_FOREST_AUDIT=true FG_FOREST_MODE="$mode" FG_FOREST_SCOPE="$scope" FG_FOREST_SOURCE="$source" \
      timeout --signal=QUIT --kill-after=15s 900s gradle --no-daemon --console=plain runServer > "$log" 2>&1);then tail -n 160 "$log";exit 1;fi
  grep 'FRESHGENERATOR_FOREST_WORLD_PASS' "$log"
  cp "$run/forest-audit.json" "$report/$label.json"
 done
 git -C "$tree" diff --exit-code -- src/main src/core src/tool
done
python3 scripts/compare_forest_audit.py "$report"
