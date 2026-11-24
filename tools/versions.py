#!/usr/bin/env python3
"""Print a value from gradle/libs.versions.toml, with no Gradle and no tomllib.

build.sh and publish.sh are deliberately Gradle-free, but their versions and
SDK levels must not drift from the Gradle build. This reads the [versions] table
with a small regex instead of requiring Python 3.11's tomllib.

    python3 tools/versions.py projectVersion   # 0.2.0
    python3 tools/versions.py compileSdk       # 35
"""

from pathlib import Path
import re
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} <key>", file=sys.stderr)
        return 2
    catalog = Path(__file__).resolve().parent.parent / "gradle" / "libs.versions.toml"
    key = sys.argv[1]
    match = re.search(rf'(?m)^\s*{re.escape(key)}\s*=\s*"([^"]*)"',
                      catalog.read_text(encoding="utf-8"))
    if not match:
        print(f"{catalog}: no [versions] key named {key!r}", file=sys.stderr)
        return 1
    print(match.group(1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
