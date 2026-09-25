#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
engine="$repo/app/src/main/java/com/excp/podroid/engine/QemuEngine.kt"
python3 - "$engine" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text()
start = text.index("if (!socketsReady) {")
end = text.index("val exitCode = withContext(dispatcher)", start)
section = text[start:end]
else_start = section.index("} else {")
bridge_call = section.index("autoStartBridge()", else_start)
safety_launch = section.index("scope.launch", else_start)
assert bridge_call < safety_launch
print("QEMU primary bridge timing check passed")
PY
