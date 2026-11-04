#!/usr/bin/env python3
"""Regenerate resources/openapi.json from the frozen docs/openapi.yaml.

The server serves resources/openapi.json at GET {path_prefix}/openapi.json
(embedded into the binary via :jolt/build {:embed ["resources"]}). The YAML
contract is FROZEN — run this only if docs/openapi.yaml ever changes:

    python3 scripts/gen-openapi-json.py

Requires: python3 + PyYAML (dev-time only; not a project dependency).
"""
import json
import pathlib
import sys

try:
    import yaml
except ImportError:
    sys.exit("PyYAML required: python3 -m pip install pyyaml")

root = pathlib.Path(__file__).resolve().parent.parent
src = root / "docs" / "openapi.yaml"
dst = root / "resources" / "openapi.json"

doc = yaml.safe_load(src.read_text())
with dst.open("w") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")

# round-trip guard: the JSON must deserialize to the YAML's exact structure
assert json.loads(dst.read_text()) == doc, "round-trip mismatch"
print(f"wrote {dst}")
