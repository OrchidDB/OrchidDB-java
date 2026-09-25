# Runnable Java examples

Run these commands from `~/orchiddb/orchiddb-java` with Java 17 or newer. On macOS, set `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` first.

| Example | Source | Run |
| --- | --- | --- |
| Native Java Gremlin traversals | [GremlinExample.java](../orchiddb-gremlin/src/test/java/io/orchiddb/gremlin/GremlinExample.java) | `./scripts/run-gremlin-example.sh` |
| Cypher on your existing DuckDB connection | [BringYourOwnDuckDb.java](../orchiddb-java/src/test/java/io/orchiddb/examples/BringYourOwnDuckDb.java) | `./scripts/run-example.sh BringYourOwnDuckDb` |
| Caller-registered Java UDF | [ClientFunctions.java](../orchiddb-java/src/test/java/io/orchiddb/examples/ClientFunctions.java) | `./scripts/run-example.sh ClientFunctions` |
| SQL compilation without executing a database | [OfflineSql.java](../orchiddb-java/src/test/java/io/orchiddb/examples/OfflineSql.java) | `./scripts/run-example.sh OfflineSql` |
| Routing to multiple engine instances | [MultipleEngines.java](../orchiddb-java/src/test/java/io/orchiddb/examples/MultipleEngines.java) | `./scripts/run-example.sh MultipleEngines` |

First-time build: `./scripts/build.sh` for the base library, or `./scripts/build-gremlin.sh` to include the optional Gremlin adapter. The existing sibling Rust checkout is required; see the [setup guide](../README.md).

Example source lives under Maven test sources so its DuckDB driver does not enter the library's runtime dependencies. Every example has a `main` method and can also be run from an IDE using the test classpath and `-Dorchiddb.native.path` pointing to the compiled JNI library.
