#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  Darwin) lib=liborchiddb_java.dylib ;;
  Linux) lib=liborchiddb_java.so ;;
  *) echo 'Use Maven exec:java directly on Windows' >&2; exit 1 ;;
esac
native_dir="${CARGO_TARGET_DIR:-$PWD/native/target}"
native_dir="$(cd "$native_dir" && pwd)"
mvn -q -Pgremlin -pl orchiddb-gremlin test-compile exec:java \
  -Dexec.classpathScope=test -Dexec.mainClass=io.orchiddb.gremlin.GremlinExample \
  -Dorchiddb.native.path="$native_dir/debug/$lib"
