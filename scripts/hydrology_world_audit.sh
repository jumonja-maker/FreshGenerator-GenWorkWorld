#!/usr/bin/env bash
# Compare packaged mods on the same native server and inspect fully generated blocks.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
seed=${1:?seed required}
[[ "$seed" =~ ^-?[0-9]+$ ]]
source_commit=${GITHUB_SHA:-$(git rev-parse HEAD)}
mkdir -p build/reports/hydrology build/hydrology-audit
report=${FG_HYDROLOGY_REPORT_DIR:-$(mktemp -d "$root/build/reports/hydrology/$seed.XXXXXX")}
mkdir -p "$report"
report=$(realpath "$report")
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'report_dir=%s\n' "$report" >> "$GITHUB_OUTPUT"
fi
printf 'HYDROLOGY_REPORT_DIR=%s\n' "$report"
java_cmd=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$java_cmd" -version
assets=$root/build/hydrology-audit
scratch=$(mktemp -d "$root/build/hydrology-world-$seed.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT

download() {
  local url=$1 dest=$2
  if [ ! -s "$dest" ]; then
    curl -fsSL --retry 3 --connect-timeout 20 --max-time 180 "$url" -o "$dest.part"
    mv "$dest.part" "$dest"
  fi
}
launcher=${FG_HYDROLOGY_LAUNCHER:-$assets/fabric-server-26.3-0.20.1.jar}
game=${FG_HYDROLOGY_GAME_JAR:-$assets/minecraft-server-26.3.jar}
api=${FG_HYDROLOGY_FABRIC_API:-$assets/fabric-api-0.165.2+26.3.jar}
download 'https://meta.fabricmc.net/v2/versions/loader/26.3/0.20.1/1.1.2/server/jar' "$launcher"
download 'https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar' "$game"
download 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.165.2+26.3/fabric-api-0.165.2+26.3.jar' "$api"
printf '%s  %s\n' '33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c' "$game" \
    '2e364989e3f6efb97e1dbd12d80d835c7ab8c26c' "$api" | sha1sum --check

if [ "${FG_HYDROLOGY_SKIP_BUILD:-false}" != true ]; then
  gradle --no-daemon --console=plain jar hydrologyAuditJar
fi
version=$(sed -n 's/^version=//p' gradle.properties)
candidate=$scratch/candidate-input.jar
cp "$root/build/libs/FreshGenerator-Fabric-26.3-$version.jar" "$candidate"
baseline=$root/dist/FreshGenerator-Fabric-26.3-0.3.6-alpha.1.jar
mkdir -p "$scratch/mods"
cp "$api" "$assets/hydrology-audit.jar" "$scratch/mods/"
cp "$launcher" "$scratch/launcher.jar"
read -r -a modes <<< "${FG_HYDROLOGY_RUNS:-candidate baseline reverse parallel reload normal-baseline normal-repeat-1 normal-repeat-2 normal-candidate distant-horizons settling}"
for mode in "${modes[@]}"; do
  preset=freshgenerator:world
  world=world-$mode
  jar=$candidate
  processors=4
  observer_mode=$mode
  settle=false
  plan_file=$report/plan.json
  fixed_sites=
  case "$mode" in
    candidate|reverse|parallel) ;;
    settling)
      observer_mode=candidate
      settle=true
      plan_file=$report/settling-plan.json
      fixed_sites=$root/scripts/fixtures/settling-$seed.json
      test -s "$fixed_sites"
      ;;
    reload) world=world-candidate ;;
    baseline) jar=$baseline ;;
    patch-baseline) jar=${FG_HYDROLOGY_PATCH_BASELINE_JAR:?initial patch JAR required} ;;
    normal-baseline|normal-repeat-1|normal-repeat-2) preset=minecraft:normal; jar=; processors=1 ;;
    normal-candidate) preset=minecraft:normal; processors=1 ;;
    distant-horizons)
      dh=${FG_HYDROLOGY_DH_JAR:-$assets/DistantHorizons-3.3.4.jar}
      download 'https://cdn.modrinth.com/data/uCdwusMi/versions/TGgEbP9A/DistantHorizons-3.3.4-26.3-fabric-neoforge.jar' "$dh"
      printf '%s  %s\n' '0b90b1b08d99974bfc3c8e86b2ceb252e2f91715ed66b925004c28335fb44e1bddbbb6b8043e4d4d53c87ed247789cd1788b1ba4e3f4394efe154e0c59cff236' "$dh" | sha512sum --check
      cp "$dh" "$scratch/mods/distant-horizons.jar"
      ;;
    *) printf 'Unknown hydrology audit mode %s\n' "$mode" >&2; exit 1 ;;
  esac
  jar_sha=
  if [ -n "$jar" ]; then
    cp "$jar" "$scratch/mods/freshgenerator.jar"
    jar_sha=$(sha256sum "$jar")
    jar_sha=${jar_sha%% *}
  else
    rm -f "$scratch/mods/freshgenerator.jar"
  fi
  printf 'eula=true\n' > "$scratch/eula.txt"
  cat > "$scratch/server.properties" <<PROPS
level-name=$world
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
  cp "$scratch/server.properties" "$report/$mode.properties"
  rm -f "$scratch/hydrology-observation.json" "$scratch"/settling-*.json
  if ! (
    cd "$scratch"
    FG_HYDROLOGY_MODE="$observer_mode" FG_HYDROLOGY_PLAN="$plan_file" FG_HYDROLOGY_JAR_SHA256="$jar_sha" \
    FG_HYDROLOGY_SETTLE="$settle" FG_HYDROLOGY_SETTLE_SITES="$fixed_sites" \
      timeout --signal=QUIT --kill-after=15s "${FG_HYDROLOGY_TIMEOUT:-480s}" \
      /usr/bin/time -f '%M' -o "$report/$mode.rss-kib" "$java_cmd" -Xms512M -Xmx2G "-XX:ActiveProcessorCount=$processors" \
      "-Dmax.bg.threads=$processors" "-Dfabric.installer.server.gameJar=$game" -jar launcher.jar nogui
  ) > "$report/$mode.log" 2>&1; then
    tail -n 100 "$report/$mode.log"
    exit 1
  fi
  if [ "$mode" = settling ]; then
    if ! grep "FRESHGENERATOR_SETTLING_COMPLETE $seed 1000" "$report/$mode.log"; then
      tail -n 100 "$report/$mode.log"
      exit 1
    fi
    cp "$scratch"/settling-*.json "$report/"
    python3 scripts/hydrology_settling_checks.py "$report"
    continue
  fi
  if ! grep 'FRESHGENERATOR_HYDROLOGY_WORLD_PASS' "$report/$mode.log"; then
    tail -n 100 "$report/$mode.log"
    exit 1
  fi
  cp "$scratch/hydrology-observation.json" "$report/$mode.json"
  if [ "$mode" = distant-horizons ]; then rm -f "$scratch/mods/distant-horizons.jar"; fi
done
if [ "${FG_HYDROLOGY_PARTIAL:-false}" != true ]; then
  python3 scripts/hydrology_checks.py "$report" "$source_commit" "$seed"
fi
