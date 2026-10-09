#!/bin/sh
# Build the self-contained metapaste binary.
set -eu
cd "$(dirname "$0")/.."
exec jolt build -m app.core -o metapaste