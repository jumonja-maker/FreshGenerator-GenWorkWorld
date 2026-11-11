#!/usr/bin/env bash
# Pure-model checks only. Does not launch Minecraft or modify any save.
set -euo pipefail
cd "$(dirname "$0")/../.."
source_file=src/core/java/dev/freshgenerator/core/CaveAccessModel.java
python3 - "$source_file" <<'PY'
import hashlib,pathlib,sys
b=pathlib.Path(sys.argv[1]).read_bytes()
h=hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
assert h=='10a4eedc311f06406386ff561a17dc817634219f', ('Model changed; review before updating baseline',h)
PY
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
javac -d "$scratch" "$source_file" research/checks/CaveSupplementalCheck.java
java -cp "$scratch" CaveSupplementalCheck
