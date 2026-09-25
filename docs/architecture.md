# Java architecture review

The compiler/executor separation is appropriate for an embedded graph query library. This is an extensible single-engine implementation, not a federation engine or a fully compatible TinkerPop provider.

## Boundaries retained

- `SqlCompiler` consumes immutable mappings, declared schemas, functions, query parameters and a dialect. It returns SQL and column names. It owns no connection and can compile offline.
- The Rust JNI library is stateless across requests except for its bounded worker runtime. Java passes versioned JSON; no JDBC or database handles cross JNI. The native compiler disables the core's default DuckDB feature.
- `ExecutionEngine` owns the session abstraction. JDBC is an adapter, not a compiler dependency. Borrowed sessions use the exact caller connection; pooled sessions retain one lease for schema discovery and execution. Neither path changes transaction settings or installs plugins/UDFs.
- `Source` binds tables to named engines. Graph mappings are immutable. Multi-engine registries work today; a graph spanning engines fails before acquiring a connection. A future federation coordinator should split physical plans and coordinate data movement rather than pretend that a cross-engine join is one SQL statement.
- `PlanCache` is optional and caches compilation output, not data. Keys include mapped schema types, query values, functions, mapping, engine and dialect. JDBC discovers only mapped columns, so an unrelated STRUCT/list column or schema addition does not break or invalidate a graph query. Mapped schema changes still invalidate plans.
- `orchiddb-gremlin` is a separately selected Maven module/artifact. It validates TinkerPop 3.7 bytecode and uses the same compiler/execution path. Consumers of `orchiddb-java` do not receive TinkerPop transitively.

## Release improvements from this review

- One Maven parent/reactor replaces duplicated POMs. `mvn verify` builds the base module; `mvn -Pgremlin verify` adds Gremlin. There is no need to install the base module before a reactor build.
- Maven coordinates use the owned domain namespace `com.orchiddb`. Java package names stay `io.orchiddb` to avoid an unnecessary API break.
- Native compiler JARs are explicit platform classifiers. `NativeSqlCompiler.load()` resolves the installed matching artifact, verifies Java version/compiler revision/checksum and extracts it to a unique temporary directory. It never downloads executable code. `load(Path)` remains available for custom builds and application-managed deployment.
- Release CI pins the Rust core commit, builds and tests each native platform, then assembles sources, Javadocs, signed POMs/JARs and classifier JARs for Central staging. All native targets must pass before upload.

## Deliberate limitations

- DuckDB is the tested execution backend. PostgreSQL SQL rendering is covered, but a PostgreSQL server is not part of current integration tests. ClickHouse and cross-engine execution are not implemented.
- Gremlin supports a documented read-only scalar subset. It buffers a bounded number of rows and returns a completed future on the calling thread. It does not provide asynchronous query execution, cursor streaming, vertex/edge/path objects, arbitrary lambdas, mutations or TinkerPop certification. Full-engine conformance figures do not establish adapter conformance.
- JDBC exceptions remain part of the engine SPI; a future non-JDBC adapter must translate failures or motivate a versioned API change. Cancellation is currently through caller-owned execution or statement timeout, not a portable public cancellation handle.
- Metadata discovery still occurs per operation. This deliberately observes the caller's temporary views and schema changes; metadata caching would need a separate invalidation contract.
- SQL includes specialized parameter literals. Cache entries and diagnostic SQL may contain sensitive values; consumers control caching/logging.
- A borrowed connection is guarded per adapter, not globally across all aliases to the same Connection. Applications must not use it concurrently outside the adapter.
- Native libraries live for the process/classloader lifetime. Sandboxed or no-exec temporary directories may require `load(Path)` from an application-approved path. A runtime checksum detects mismatch/corruption; it is not a substitute for trusting/signature-verifying the artifact source.
- The compiler worker count is bounded, but admission/queue limits are not. Applications accepting untrusted queries should impose their own request limits; hard compile cancellation is not implemented.

## Evidence

Tests execute against a caller-owned DuckDB session, compare native Java Gremlin results with TinkerGraph, cover lease/transaction ownership and cache invalidation, and compile with no DuckDB runtime dependency. A separate fresh-JVM check uses packaged API/native JARs only and rejects mismatched version, core revision and checksum metadata. The release workflow repeats integration and artifact checks on every release platform; configuring that workflow does not imply it has already run on every platform.

## Arrow execution boundary

`Session.executeArrow` is the batch extension point, with `Session.execute` as a
row convenience for Arrow adapters. `Graph.queryArrow` couples a result to its
session lease. JDBC adapters explicitly accept an application-supplied exporter
and allocator; no DuckDB classes are imported by the production API. Drivers that
cannot export Arrow must reject batch execution rather than silently convert rows.
Arrow results own query resources and child allocators; connections, pools and
parent allocators retain caller ownership. See [Arrow contracts](arrow.md).

Arrow vectors are now a public API dependency. DuckDB JDBC, Arrow's C Data bridge
and the test allocator remain test dependencies. The existing SQL-only compiler
and JNI transport have no result-data responsibility.
