#!/usr/bin/env bash
# Fresh throwaway worlds; never a user's world. All cases use the ONE public generator.
set -euo pipefail
mkdir -p run build/reports/technical
printf 'eula=true\n' > run/eula.txt
for seed in 42 1337 -20261111; do
  for mode in fg vanilla; do
    label="$mode-$seed"; preset=minecraft:normal; expected=minecraft:overworld
    if [ "$mode" = fg ]; then preset=freshgenerator:world; expected=freshgenerator:highlands_v2; fi
    cat > run/server.properties <<EOF
level-name=coherent-$label
level-seed=$seed
level-type=$preset
generate-structures=true
online-mode=false
server-ip=127.0.0.1
server-port=25579
view-distance=2
simulation-distance=2
max-tick-time=600000
spawn-protection=0
EOF
    rm -f run/freshgenerator-audit.json run/freshgenerator-envelope.json
    FRESHGENERATOR_AUDIT=true FG_AUDIT_CASE="$label" FG_AUDIT_SETTINGS="$expected" timeout --signal=QUIT --kill-after=15s 720s \
      gradle --no-daemon --console=plain runServer > "build/reports/technical/$label.log" 2>&1 || {
        tail -n 180 "build/reports/technical/$label.log"; exit 1;
      }
    grep 'AUDIT_STAGE\|FRESHGENERATOR_ENVELOPE_PASS' "build/reports/technical/$label.log" || true
    if ! grep -q 'FRESHGENERATOR_AUDIT_PASS' "build/reports/technical/$label.log"; then
      tail -n 180 "build/reports/technical/$label.log"; exit 1
    fi
    cp run/freshgenerator-audit.json "build/reports/technical/$label.json"
    cp run/server.properties "build/reports/technical/$label.properties"
    if [ "$mode" = fg ]; then
      grep -q 'FRESHGENERATOR_ENVELOPE_PASS' "build/reports/technical/$label.log"
      cp run/freshgenerator-envelope.json "build/reports/technical/$label-envelope.json"
    fi
  done
done
python3 scripts/summarize_technical_audit.py
