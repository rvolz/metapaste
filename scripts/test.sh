#!/bin/sh
# Run every clojure.test namespace under test/. Exits nonzero on failure.
# -M:test loads the test-only deps alias (jolt.http-client for the
# integration suite).
set -eu
cd "$(dirname "$0")/.."
exec jolt -M:test scripts/run_tests.clj