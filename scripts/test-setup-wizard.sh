#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
screen="$repo/app/src/main/java/com/excp/podroid/ui/screens/setup/SetupScreen.kt"
python3 - "$screen" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text()

def section(name, end):
    start = text.index(f"private fun {name}(")
    stop = text.index(end, start)
    return text[start:stop]

download = section("SystemImageDownloadPage", "// ── Page 1: Storage")
storage = section("StoragePage", "// ── Page 2: VM config")
nav = section("SetupNavBar", "}")

assert "OutlinedTextField" not in download
assert "system_image_kernel_url" not in download
assert "nextEnabled = downloaded" in download
assert "onBack" in storage
assert "SetupNavBar" in storage
assert "enabled = nextEnabled" in nav
print("setup wizard contract passed")
PY
