# OrchidDB for Java

**Start here: [Runnable examples](examples/README.md)** — Arrow batches, Gremlin, existing DuckDB connections, UDFs, SQL-only compilation, and multiple engines.

Compile Cypher, Gremlin and mapped SPARQL queries to SQL, then execute them on **your existing database connection**. This library contains no DuckDB database, driver, connection pool, SQLg layer, or result cache. Its JNI library contains the OrchidDB compiler, built with `default-features = false`.

The application owns the engine: choose your DuckDB JDBC version, configure extensions and Iceberg credentials, register UDFs, configure caching, and decide when to commit. OrchidDB reads source metadata and runs the generated SELECT on the **same connection**, including temporary tables and functions. It never reopens a JDBC URL or rebuilds your views.

[Architecture review](docs/architecture.md) · [Maven Central release guide](docs/releases.md)

## Build and try it

Keep the two repositories next to one another. The compatible core revision is recorded in `native/CORE_REVISION`; CI checks out that exact revision:

```text
~/orchiddb/
  orchiddb/       # Rust compiler
  orchiddb-java/  # Maven parent: orchiddb-java API module + optional orchiddb-gremlin
```

Requirements: Rust 1.93 or newer, a platform C toolchain, Maven 3.9+, and Java 17+. First builds compile DataFusion and can take several minutes. No DuckDB C++ build is needed. On macOS, select Java 17+ if your shell still defaults to Java 8:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd ~/orchiddb/orchiddb-java
./scripts/build.sh
./scripts/run-example.sh ArrowBatches
./scripts/run-example.sh BringYourOwnDuckDb
./scripts/run-example.sh ClientFunctions
./scripts/run-example.sh OfflineSql
./scripts/run-example.sh MultipleEngines
./scripts/check-dependencies.sh
```

`build.sh` builds the native compiler and runs integration tests. `check-dependencies.sh` verifies the driver-free dependency graph. The resulting JAR is `orchiddb-java/target/orchiddb-java-0.1.0-SNAPSHOT.jar`. Native output is `native/target/debug/liborchiddb_java.dylib` on macOS or `liborchiddb_java.so` on Linux. A release native build uses `cargo build --manifest-path native/Cargo.toml --locked --release`; load the library from `native/target/release/` instead. Native binaries must match your JVM's OS and architecture. On Windows, build with Cargo, then run Maven with `-Dorchiddb.native.path=C:\absolute\path\orchiddb_java.dll`.

To install the development JAR in your local Maven repository, run `mvn install` after building. These coordinates are local development coordinates, not a published Maven Central package:

```xml
<dependency>
  <groupId>com.orchiddb</groupId>
  <artifactId>orchiddb-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Add your chosen JDBC driver separately. DuckDB JDBC appears only in this project's **test** dependencies so examples can run. The base JAR depends on Jackson and Arrow vectors; the application supplies its Arrow memory implementation and any driver-specific export support. Load your own compiler with `NativeSqlCompiler.load(Path)`, or use `load()` with a matching platform-classifier JAR as described in the [release guide](docs/releases.md). Classpath loading verifies and extracts the installed compiler; it never downloads code or contacts a database.

## Bring your own DuckDB

```java
var compiler = NativeSqlCompiler.load(Path.of("/absolute/path/liborchiddb_java.dylib"));
var people = Source.table("lake", "people");
var friendships = Source.table("lake", "knows");
var mapping = new GraphMapping(
    List.of(NodeMapping.node("Person", people, "id").property("name", "name")),
    List.of(EdgeMapping.edge("KNOWS", friendships, "id", "src", "dst", "Person", "Person")));

// connection was created and configured by your application.
var db = new OrchidDB(compiler, PlanCache.bounded(128),
    JdbcEngine.borrowed("lake", SqlDialect.DUCKDB, connection));
var graph = db.graph(mapping);
var query = Query.cypher(
    "MATCH (a:Person)-[:KNOWS]->(b:Person) WHERE a.name=$name RETURN b.name AS friend",
    Map.of("name", "Ada"));
try (var rows = graph.query(query)) {
    while (rows.next()) System.out.println(rows.get("friend"));
}

// Or take the SQL and do all execution yourself:
CompiledQuery plan = graph.plan(query);
try (var statement = connection.createStatement();
     var rows = statement.executeQuery(plan.sql())) {
    while (rows.next()) System.out.println(rows.getString(1));
}
```

See [BringYourOwnDuckDb.java](orchiddb-java/src/test/java/io/orchiddb/examples/BringYourOwnDuckDb.java) for a complete runnable program with table creation and data. [OfflineSql.java](orchiddb-java/src/test/java/io/orchiddb/examples/OfflineSql.java) compiles against explicit schema metadata without opening a database at all.

`Source.table("lake", "catalog", "schema", "table")` uses distinct literal identifier parts; names are quoted, never interpolated as raw SQL. Use two parts for `schema.table`. Node IDs and edge IDs/endpoints must be signed integer columns, unique within each node label or edge type. Supply a distinct edge ID even for parallel edges. OrchidDB validates metadata but does not scan data to prove uniqueness or referential integrity. NULL or duplicate identities violate the mapping contract.

## Arrow batches

Use `graph.queryArrow(query)` for columnar results. Configure your JDBC adapter
with `withArrow(exporter, allocator, batchSize)` to use your driver's native Arrow
stream. OrchidDB receives vectors directly; it does not reconstruct them from
JDBC rows. The allocator and connection remain caller-owned. See the
[Arrow guide](docs/arrow.md) for runnable setup, dependencies, JVM settings and
buffer lifetimes. The row API becomes a convenience view over Arrow when this
adapter is configured.

## Ownership and execution

- `JdbcEngine.borrowed(...)` uses the exact `Connection`. It never closes, commits, rolls back, or configures it. One active library session is allowed per borrowed adapter. Overlapping use fails immediately; close the result before reusing it. External use of that same connection remains your responsibility; do not create multiple adapters to bypass serialization.
- `JdbcEngine.pooled(...)` borrows a connection from your `DataSource` for each operation. Schema discovery and query execution use one lease. Closing the result returns that lease. Configure extensions, UDFs and session initialization through your pool so every borrowed session has them. OrchidDB never closes the pool.
- Without Arrow configuration, `QueryResult` wraps the driver's row cursor and owns its statement. Always use try-with-resources, including after early iteration. JDBC drivers control actual buffering. `get(int)` is 1-based; `get(String)` rejects ambiguous duplicate column names. Values are driver-native JDBC values, including `java.sql.Array`/`Struct` where applicable.
- Optional `fetchSize` and `timeoutSeconds` overloads configure library-created statements. OrchidDB never changes autocommit to obtain driver-specific streaming behavior. For full control, compile and execute the returned SQL yourself.
- Errors in schema discovery, planning, or execution release acquired resources. Closing a borrowed result leaves its connection and transaction open.

## Functions, plugins and Iceberg

Register actual functions using the engine's API, then declare their compiler signatures. For example, [ClientFunctions.java](orchiddb-java/src/test/java/io/orchiddb/examples/ClientFunctions.java) uses DuckDB's own Java UDF API on the caller's connection:

```java
DuckDBFunctions.scalarFunction().withName("java_next_age")
    .withParameter(long.class).withReturnType(long.class)
    .withLongFunction(age -> age + 1).register(connection);
var signature = FunctionSignature.scalar("nextAge", "java_next_age", "int64", "int64");
var graph = db.graph(mapping, List.of(signature));
// MATCH (p:Person) RETURN nextAge(p.age)
```

Signatures describe functions already installed in the engine. They don't evaluate a function during planning or install an implementation. The compiler checks declared argument types and inserts typed casts; overloads can be declared separately. Plugins and SQL macros use the same declaration mechanism. Graph-language built-ins take precedence.

For Iceberg, configure your own connection using the DuckDB Iceberg extension and your catalog's documented authentication, then expose node and edge tables as views. Map those views just like local tables. OrchidDB does not install extensions, fetch secrets, select a catalog, or implement a second Iceberg cache. Illustrative application-owned setup, after `LOAD iceberg` and catalog configuration:

```sql
CREATE TEMP VIEW people AS
SELECT CAST(person_id AS BIGINT) AS id, full_name AS name
FROM lake.analytics.people;
CREATE TEMP VIEW knows AS
SELECT CAST(edge_id AS BIGINT) AS id,
       CAST(source_id AS BIGINT) AS src, CAST(target_id AS BIGINT) AS dst
FROM lake.analytics.friendships;
```

This catalog example needs your real Iceberg catalog and credentials; it is not part of the offline integration tests. Session-local views work because no second connection is opened. Keep complex extension-specific columns out of mapped source views, or cast them to supported types.

## Queries and caching

The library compiles read queries. Mutations, external SERVICE access, opaque extensions and plans requiring engine-managed materialization fail during planning; there is no hidden interpreter/database fallback. Language conformance of the full OrchidDB runtime does **not** imply every construct lowers to standalone SQL. Unsupported relational constructs report `PlanningException`.

Cypher parameters are bound into the parsed AST as typed literals, with SQL escaping handled by the compiler. They are **not JDBC `?` placeholders**. SQL may contain sensitive parameter values: avoid logging it. Supported Java parameter values are null, strings, booleans, signed integral wrappers, finite floats/doubles, lists and maps of these. Some compound operations require runtime values and cannot lower to SQL. Temporal/decimal Java objects need explicit query casts or a source view; they aren't silently serialized to strings. Gremlin and SPARQL bindings are currently rejected; SPARQL takes an explicit `Ontology` mapping for classes, properties, and relationships.

`PlanCache.none()` is the conservative default option; `PlanCache.bounded(n)` is an optional synchronized LRU. Implement `PlanCache` to use your application cache. Keys are immutable `Compilation` snapshots containing the engine ID, dialect, query, parameter **values**, mappings, ontology, function signatures, and schema. Data is never cached. Metadata is rediscovered on each connected call so schema changes generate new keys. Use `NativeSqlCompiler.compile(Compilation)` directly for offline schema snapshots and your own metadata/cache invalidation policy. Schema discovery and execution are not atomic with concurrent external DDL; coordinate schema changes in the application.

Schema types: `boolean`, `int8`, `int16`, `int32`, `int64`, `float32`, `float64`, `string`, `binary`, `date`, `timestamp` (microseconds without timezone), and `decimal:precision:scale` (precision 1–38). Unsigned integers, nanosecond or zoned timestamps, arrays/structs and extension-specific source types require a cast/view in this first JDBC adapter. Unsupported types fail explicitly.

## Multiple engines and future federation

[MultipleEngines.java](orchiddb-java/src/test/java/io/orchiddb/examples/MultipleEngines.java) registers two independent engines and routes each graph to its named source. Engine IDs are part of every source mapping and compiled plan. Plans cannot be executed through a mismatched engine/dialect.

`SqlCompiler`, `ExecutionEngine`, and `ExecutionEngine.Session` are separate interfaces. JDBC is one execution adapter; a future ClickHouse HTTP adapter can implement the session interface without exposing a JDBC connection. Dialect identity is separate from transport. The native compiler renders DuckDB and PostgreSQL SQL; DuckDB execution is integration-tested, while PostgreSQL rendering is tested offline. PostgreSQL live execution is not yet certified. ClickHouse compilation is explicitly unsupported today.

Cross-engine graphs fail before a connection is acquired. Future federation needs a coordinator that partitions Graph IR by source/capabilities, sends each fragment to its dialect compiler, and handles exchanges, joins, cancellation, and transaction boundaries. The source IDs and engine sessions provide those integration points without pretending a single engine can execute another engine's tables.

## Implementation references

The separation of a Java API and native component follows [DuckDB Java](https://github.com/duckdb/duckdb-java). Caller-defined functions use its [official UDF API](https://github.com/duckdb/duckdb-java/blob/main/UDF.MD). The OrchidDB JNI boundary is a stateless, versioned JSON request/response with no database handles; planning runs on a bounded native worker pool with an independent stack. Rust panics become planning errors at the boundary.

See [LICENSE.md](LICENSE.md) for the project's license terms.

## Optional native Java Gremlin API

The optional [orchiddb-gremlin module](orchiddb-gremlin/README.md) lets you write
`g.V().has("name", "Ada").out("KNOWS").values("name").toList()` against your
existing `OrchidDB.Graph`. It adds TinkerPop only when explicitly selected; the
base library has no TinkerPop dependency. This first adapter supports read-only
scalar results and rejects unsupported traversal semantics explicitly.

Build and run its example with `./scripts/build-gremlin.sh` and
`./scripts/run-gremlin-example.sh`.
