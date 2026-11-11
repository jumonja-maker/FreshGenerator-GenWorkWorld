#!/usr/bin/env bash
# Generate disposable FreshGenerator worlds and measure their surface biome junctions.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$#" -eq 0 ]; then set -- 42 1337 -20261111; fi
mkdir -p build/reports/biome-transitions
run_root=$(mktemp -d build/biome-audit.XXXXXX)
trap 'rm -rf -- "$run_root"' EXIT
report_dir=$(mktemp -d build/reports/biome-transitions/run.XXXXXX)

for seed in "$@"; do
  if ! [[ "$seed" =~ ^-?[0-9]+$ ]]; then
    printf 'Invalid seed: %s\n' "$seed" >&2
    exit 1
  fi
  run_dir="$run_root/seed-$seed"
  mkdir -p "$run_dir"
  printf 'eula=true\n' > "$run_dir/eula.txt"
  cat > "$run_dir/server.properties" <<EOF
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
  log="$report_dir/seed-$seed.log"
  if ! FRESHGENERATOR_AUDIT=true FG_AUDIT_MODE=biome-transitions FG_AUDIT_RUN_DIR="$run_dir" FG_AUDIT_SETTINGS= \
    timeout --signal=QUIT --kill-after=15s 720s gradle --no-daemon --console=plain runServer > "$log" 2>&1; then
    tail -n 100 "$log" >&2
    exit 1
  fi
  grep 'FRESHGENERATOR_BIOME_AUDIT_PASS' "$log"
  cp "$run_dir/freshgenerator-biome-transitions.json" "$report_dir/seed-$seed.json"
done
printf 'Biome transition reports: %s\n' "$report_dir"
