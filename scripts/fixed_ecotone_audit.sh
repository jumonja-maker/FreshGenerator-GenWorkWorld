#!/usr/bin/env bash
# Identical observer; separate temporary worlds and unchanged production sources on each side.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)
mkdir -p build/reports/ecotone-fixed
scratch=$(mktemp -d "$root/build/ecotone-ab.XXXXXX")
cleanup() {
  for mode in reference candidate; do git worktree remove --force "$scratch/$mode" >/dev/null 2>&1 || true; done
  rm -rf -- "$scratch"
}
trap cleanup EXIT
reference=4448d9dcf95feea951890d8ef65d8e8b8163af13
candidate=$(git rev-parse HEAD)
for mode in reference candidate; do
  source=$reference; if [ "$mode" = candidate ]; then source=$candidate; fi
  tree="$scratch/$mode"
  git worktree add --detach "$tree" "$source"
  cp "$root/src/audit/java/dev/freshgenerator/audit/FixedEcotoneAudit.java" "$tree/src/audit/java/dev/freshgenerator/audit/FixedEcotoneAudit.java"
  # Change only the isolated test entrypoint. No production file is modified.
  python3 - "$tree/src/audit/resources/fabric.mod.json" <<'PY'
import json,sys,pathlib
p=pathlib.Path(sys.argv[1]);v=json.loads(p.read_text())
v['entrypoints']['main']=['dev.freshgenerator.audit.FixedEcotoneAudit']
p.write_text(json.dumps(v,indent=2)+'\n')
PY
  for seed in 42 1337 -20261111; do
    run="$tree/run-fixed/seed-$seed"; mkdir -p "$run"
    printf 'eula=true\n' > "$run/eula.txt"
    cat > "$run/server.properties" <<EOF
level-name=world
level-seed=$seed
level-type=freshgenerator:world
generate-structures=false
online-mode=false
server-ip=127.0.0.1
server-port=0
view-distance=2
simulation-distance=2
max-tick-time=600000
spawn-protection=0
EOF
    log="$root/build/reports/ecotone-fixed/$mode-$seed.log"
    if ! (cd "$tree"; FRESHGENERATOR_AUDIT=true FG_AUDIT_RUN_DIR="$run" FG_FIXED_ECOTONE=true FG_FIXED_MODE="$mode" FG_FIXED_SOURCE="$source" \
       timeout --signal=QUIT --kill-after=15s 720s gradle --no-daemon --console=plain runServer > "$log" 2>&1); then tail -n 160 "$log";exit 1;fi
    grep 'FRESHGENERATOR_FIXED_ECOTONE_PASS' "$log"
    cp "$run/fixed-ecotone.json" "$root/build/reports/ecotone-fixed/$mode-$seed.json"
  done
  git -C "$tree" diff --exit-code -- src/main src/core src/tool
  if [ "$mode" = reference ]; then
    (cd "$tree"; gradle --no-daemon --console=plain jar > "$root/build/reports/ecotone-fixed/reference-jar.log" 2>&1)
    python3 - "$tree" <<'PY'
import json,zipfile,sys,pathlib
p=pathlib.Path(sys.argv[1]);a=p/'build/libs/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar';b=p/'dist/FreshGenerator-Fabric-26.3-0.2.3-alpha.1.jar'
with zipfile.ZipFile(a) as x,zipfile.ZipFile(b) as y:
 for n in y.namelist():
  if n.startswith('data/') and n.endswith('.json'): assert json.loads(x.read(n))==json.loads(y.read(n)),n
  if n.endswith('.class'): assert x.read(n)==y.read(n),n
print('REFERENCE_PRODUCTION_MATCHES_PUBLISHED_0_2_3')
PY
  fi
done
python3 scripts/compare_ecotone_audit.py
