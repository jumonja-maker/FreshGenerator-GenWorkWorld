#!/usr/bin/env bash
# Pure-model check only: does not launch Minecraft or validate ecological fidelity.
set -euo pipefail
cd "$(dirname "$0")/../.."
source_file=src/core/java/dev/freshgenerator/core/ForestEcology.java
expected=374f9ab1f1181c608030f95e8bb987c5940c1a70
actual=$(git hash-object "$source_file")
if [ "$actual" != "$expected" ]; then
  printf 'Model changed: expected %s, got %s. Review the check target before reuse.\n' "$expected" "$actual" >&2
  exit 1
fi
tmp=$(mktemp -d "${TMPDIR:-/tmp}/fg-forest-model.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT
out=${1:-build/reports/forest-supplemental-model-check.json}
mkdir -p "$(dirname "$out")"
javac -d "$tmp" "$source_file" research/checks/ForestSupplementalCheck.java
java -cp "$tmp" ForestSupplementalCheck "$out"
