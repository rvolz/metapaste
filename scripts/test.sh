#!/bin/sh
# Run every clojure.test namespace under test/. Exits nonzero on failure.
set -eu
cd "$(dirname "$0")/.."
exec jolt scripts/run_tests.clj