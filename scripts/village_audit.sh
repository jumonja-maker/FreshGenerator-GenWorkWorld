#!/usr/bin/env bash
# Real baseline -> new village terrain -> exact saved-world reload, same coordinates.
set -euo pipefail
cd "$(dirname "$0")/.."
seed=${1:?seed};[[ "$seed" = 42 || "$seed" = 1337 || "$seed" = -20261111 ]]
root=$PWD;baseline=7c661cecbc42328c4ef099b034393cc36afb8b50
source=${GITHUB_SHA:-$(git rev-parse HEAD)}
report="$root/build/reports/village/$seed";mkdir -p "$report"
scratch=$(mktemp -d "$root/build/village-ab.XXXXXX")
cleanup(){ for phase in baseline candidate;do git worktree remove --force "$scratch/$phase" >/dev/null 2>&1||true;done;rm -rf -- "$scratch"; }
trap cleanup EXIT
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=1 -Dmax.bg.threads=1"
for phase in baseline candidate;do
 ref=$baseline;[ "$phase" = candidate ]&&ref=$source
 tree="$scratch/$phase";git worktree add --detach "$tree" "$ref"
 cp src/audit/java/dev/freshgenerator/audit/VillageWorldAudit.java "$tree/src/audit/java/dev/freshgenerator/audit/"
 if [ "$phase" = baseline ];then
  mkdir -p "$tree/src/audit/java/dev/freshgenerator/village"
  cp src/main/java/dev/freshgenerator/village/VillageTerrainModel.java "$tree/src/audit/java/dev/freshgenerator/village/"
 fi
 python3 - "$tree" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1])/'src/audit/resources/fabric.mod.json'
j=json.loads(p.read_text());j['entrypoints']['main']=['dev.freshgenerator.audit.VillageWorldAudit'];j.pop('mixins',None);p.write_text(json.dumps(j)+'\n')
PY
 run="$tree/village-run";mkdir -p "$run";printf 'eula=true\n'>"$run/eula.txt"
 cat > "$run/server.properties" <<PROPS
level-name=world
level-seed=$seed
level-type=freshgenerator:world
generate-structures=true
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
max-tick-time=600000
spawn-protection=0
PROPS
 passes=1;[ "$phase" = candidate ]&&passes=2
 for ((i=1;i<=passes;i++));do
  label=$phase;[ "$i" = 2 ]&&label=reload
  if ! (cd "$tree";FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_VILLAGE_AUDIT=true FG_VILLAGE_SOURCE="$ref" FG_VILLAGE_PHASE="$label" FG_VILLAGE_LOCATIONS="$report/locations.json" timeout --signal=QUIT --kill-after=15s 1500s gradle --no-daemon --console=plain runServer > "$report/$label.log" 2>&1);then
   [ ! -f "$run/village-audit.json" ]||cp "$run/village-audit.json" "$report/$label.json"
   tail -n 100 "$report/$label.log";exit 1
  fi
  cp "$run/village-audit.json" "$report/$label.json"
  grep 'FRESHGENERATOR_VILLAGE_AUDIT_PASS' "$report/$label.log"
 done
 git -C "$tree" diff --exit-code -- src/main src/core src/tool
done
python3 scripts/village_checks.py "$report" "$source" "$seed"
