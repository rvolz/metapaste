#!/bin/sh
# Build the self-contained metapaste binary.
set -eu
cd "$(dirname "$0")/.."

# jolt's build.ss expects the csv<ver>/<machine> dir (it holds scheme.h).
# Derive it when JOLT_CHEZ_CSV is not set — an explicit value always wins.
# Two layouts: a standard prefix (bin/scheme + lib/csv<ver>/<machine>) and
# Chez's own install where bin/scheme is a link INTO the csv dir (then the
# resolved interpreter's own directory is the csv dir). Wrapper scripts
# (~/.local/bin/chez exec'ing the real scheme) are unwrapped by asking the
# interpreter for its usage line, which prints argv[0] as resolved.
if [ -z "${JOLT_CHEZ_CSV:-}" ]; then
  exe=$(command -v chez 2>/dev/null || command -v chezscheme 2>/dev/null || true)
  if [ -n "$exe" ]; then
    interp=$("$exe" --help 2>&1 | sed -n 's/^usage: \([^ ]*\).*/\1/p' || true)
    [ -n "$interp" ] || interp=$exe
    while [ -L "$interp" ]; do
      link=$(readlink "$interp")
      case "$link" in
        /*) interp=$link ;;
        *) interp=$(CDPATH= cd -- "$(dirname -- "$interp")" && pwd)/$link ;;
      esac
    done
    csvdir=$(dirname -- "$interp")
    if [ ! -f "$csvdir/scheme.h" ]; then
      ver=$("$interp" --version | awk '{print $NF}')
      prefix=$(CDPATH= cd -- "$csvdir/.." && pwd)
      for d in "$prefix"/lib/csv"$ver"/*; do
        if [ -f "$d/scheme.h" ]; then csvdir=$d; break; fi
      done
    fi
    if [ -f "$csvdir/scheme.h" ]; then
      JOLT_CHEZ_CSV=$csvdir
      export JOLT_CHEZ_CSV
    fi
  fi
fi

exec jolt build -m app.core -o metapaste
