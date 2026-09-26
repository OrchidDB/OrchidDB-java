#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
example="${1:-BringYourOwnDuckDb}"
case "$example" in BringYourOwnDuckDb|ClientFunctions|OfflineSql|MultipleEngines|ArrowBatches|GremlinExample) ;; *) echo 'Unknown example' >&2; exit 1;; esac
profile=""
main_class="io.orchiddb.examples.$example"
if [[ "$example" == GremlinExample ]]; then
  profile=-Pgremlin
  main_class=io.orchiddb.gremlin.GremlinExample
fi
# The standalone example POM resolves the published API and compiler from Maven Central.
mvn -q ${profile:+"$profile"} -f examples/pom.xml compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java --add-opens=java.base/java.nio=ALL-UNNAMED \
  -cp "examples/target/classes:$(cat examples/target/classpath.txt)" "$main_class"
