#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Install the base artifact locally, then verify the independently optional adapter.
./scripts/build.sh "$@"
mvn -q install -DskipTests -Dspotless.skip=true "$@"
case "$(uname -s)" in
  Darwin) lib=liborchiddb_java.dylib ;;
  Linux) lib=liborchiddb_java.so ;;
  *) echo 'Use Maven with -Dorchiddb.native.path pointing to your compiled JNI library.' >&2; exit 1 ;;
esac
native_dir="${CARGO_TARGET_DIR:-$PWD/native/target}"
native_dir="$(cd "$native_dir" && pwd)"
mvn -f orchiddb-gremlin/pom.xml -Dorchiddb.native.path="$native_dir/debug/$lib" verify "$@"
