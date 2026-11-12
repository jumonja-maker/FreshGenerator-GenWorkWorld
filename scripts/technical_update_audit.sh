#!/usr/bin/env bash
# Same current-climate observer, three controls, candidate and saved-world reload.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/test_technical_observer.py
seed=${1:-42};scope=${2:-fresh}
[[ "$seed" =~ ^-?[0-9]+$ ]];[[ "$scope" = fresh || "$scope" = normal ]]
root=$PWD;reference=8bb647170ed20390fc8ae3b9e05017fbd921aa61
candidate=${GITHUB_SHA:-$(git rev-parse HEAD)}
report="$root/build/reports/technical-update/$scope-$seed";mkdir -p "$report"
scratch=$(mktemp -d "$root/build/technical-ab.XXXXXX")
cleanup(){ for mode in reference candidate;do git worktree remove --force "$scratch/$mode" >/dev/null 2>&1||true;done;rm -rf -- "$scratch"; }
trap cleanup EXIT
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=1 -Dmax.bg.threads=1 -Dfreshgenerator.forestAudit=true -Dfreshgenerator.caveAudit=true"
for mode in reference candidate;do
 source=$reference;[ "$mode" = candidate ]&&source=$candidate
 tree="$scratch/$mode";git worktree add --detach "$tree" "$source"
 python3 - "$root" "$tree" <<'PY'
import hashlib,json,pathlib,sys
root,tree=map(pathlib.Path,sys.argv[1:])
sys.path.insert(0,str(root/'scripts'))
from technical_observer import current_climate_observer
path='src/audit/java/dev/freshgenerator/audit/BiomeRegionWorldAudit.java'
s=current_climate_observer((root/path).read_text())
needle='Files.writeString(Path.of("biome-region-audit.json"),G.toJson(out)+"\\n");'
assert s.count(needle)==1
s=s.replace(needle,'out.add("forest_telemetry",G.toJsonTree(dev.freshgenerator.forest.ForestTelemetry.snapshot()));\n'
 '  out.add("cave_telemetry",G.toJsonTree(dev.freshgenerator.cave.CaveTelemetry.snapshot()));\n'
 '  out.add("forest_height_oracle",G.toJsonTree(dev.freshgenerator.audit.ForestHeightOracle.snapshot()));\n'
 '  out.addProperty("technical_climate_observer","current-on-both-versions");\n'
 '  out.addProperty("observer_sha256",System.getenv("FG_TECH_OBSERVER"));\n  '+needle)
needle='Map<Object,byte[]> encodings=new IdentityHashMap<>();'
assert s.count(needle)==1
s=s.replace(needle,needle+'java.util.List<String> palette=new java.util.ArrayList<>();java.util.Map<String,Integer> stateIds=new java.util.HashMap<>();int[] observedStates=new int[256*384];int observation=0;')
needle='blocks.update(encodings.computeIfAbsent(b,k->(k.toString()+"\\n").getBytes(StandardCharsets.UTF_8)));'
assert s.count(needle)==1
s=s.replace(needle,needle+'String description=b.toString();observedStates[observation++]=stateIds.computeIfAbsent(description,k->{palette.add(k);return palette.size()-1;});')
needle='out.add("biomes",G.toJsonTree(types));'
assert s.count(needle)==1
s=s.replace(needle,needle+'out.add("block_palette",G.toJsonTree(palette));out.add("sampled_states",G.toJsonTree(observedStates));')
(tree/path).write_text(s)
# Identical read-only instrumentation on both versions; production files stay untouched.
observed={path:s}
for extra in ['src/audit/java/dev/freshgenerator/audit/ForestHeightOracle.java',
              'src/audit/java/dev/freshgenerator/audit/mixin/ForestHeightOracleMixin.java']:
 text=(root/extra).read_text();dest=tree/extra;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text);observed[extra]=text
config='freshgenerator-technical-audit.mixins.json'
text=json.dumps({'required':True,'minVersion':'0.8','package':'dev.freshgenerator.audit.mixin',
                 'compatibilityLevel':'JAVA_25','mixins':['ForestHeightOracleMixin'],
                 'injectors':{'defaultRequire':1}},sort_keys=True,indent=2)+'\n'
(tree/'src/audit/resources'/config).write_text(text);observed[config]=text
p=tree/'src/audit/resources/fabric.mod.json';j=json.loads(p.read_text());j['entrypoints']['main']=['dev.freshgenerator.audit.BiomeRegionWorldAudit'];j['mixins']=[config];p.write_text(json.dumps(j,indent=2)+'\n')
observed['audit-entrypoints']=json.dumps({'main':j['entrypoints']['main'],'mixins':j['mixins']},sort_keys=True)
(tree/'observer.sha256').write_text(hashlib.sha256(json.dumps(observed,sort_keys=True).encode()).hexdigest())
PY
 run="$tree/technical-run";mkdir -p "$run";printf 'eula=true\n'>"$run/eula.txt"
 preset=freshgenerator:world;[ "$scope" = normal ]&&preset=minecraft:normal
 cat > "$run/server.properties" <<PROPS
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
PROPS
 passes=2;[ "$mode" = reference ]&&passes=3
 for ((i=1;i<=passes;i++));do
  label=$mode
  if [ "$i" -ge 2 ];then
   label=candidate-reload
   if [ "$mode" = reference ];then
    label=reference-repeat;[ "$i" = 3 ]&&label=reference-repeat-2
    repeated="$tree/technical-$label";mkdir -p "$repeated"
    cp "$run/server.properties" "$run/eula.txt" "$repeated/"
    run=$repeated
   fi
  fi
  log="$report/$label.log"
  if ! (cd "$tree";FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_REGION_AUDIT=true FG_REGION_MODE="$mode" FG_REGION_PASS="$label" FG_REGION_SCOPE="$scope" FG_REGION_SOURCE="$source" FG_REGION_COORDINATES="$report/coordinates.json" FG_TECH_OBSERVER="$(cat observer.sha256)" \
   timeout --signal=QUIT --kill-after=15s 1200s gradle --no-daemon --console=plain runServer > "$log" 2>&1);then
   [ ! -f "$run/biome-region-audit.json" ]||cp "$run/biome-region-audit.json" "$report/$label.json"
   tail -n 120 "$log";exit 1
  fi
  grep 'FRESHGENERATOR_BIOME_REGION_WORLD_PASS' "$log"
  if grep -q 'Forest height oracle:' "$log";then tail -n 120 "$log";exit 1;fi
  cp "$run/biome-region-audit.json" "$report/$label.json"
  python3 - "$report/$label.json" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['technical_climate_observer']=='current-on-both-versions'
PY
 done
 git -C "$tree" diff --exit-code -- src/main src/core src/tool
done
python3 scripts/technical_update_checks.py "$report" "$candidate" "$scope" "$seed"
