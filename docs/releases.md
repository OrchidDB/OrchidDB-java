# Publishing Java releases to Maven Central

Repository: https://github.com/OrchidDB/OrchidDB-java

Version 0.1.0 is published on Maven Central, including the macOS ARM64 compiler JAR. The release workflow defaults to staging a signed release for validation and manual publication in the Central Portal. The checked-in license is GPL-3.0-only.

## Coordinates and artifacts

| Artifact | Contents |
| --- | --- |
| `com.orchiddb:orchiddb-parent` | Shared Maven parent POM |
| `com.orchiddb:orchiddb-java` | Java API, SQL compiler bridge, JDBC adapter; sources and Javadocs |
| `com.orchiddb:orchiddb-java:jar:macos-aarch64` | Apple Silicon compiler; 0.1.0 built and tested locally on macOS 26 |
| `com.orchiddb:orchiddb-gremlin` | Optional TinkerPop adapter; sources and Javadocs |

The classifier JARs contain **OrchidDB's compiler**, not DuckDB. Consumers choose their own JDBC driver. Version 0.1.0 does not include compiler packages for other OS/CPU combinations. Maven resolves the compiler JAR, and `NativeSqlCompiler.load()` extracts and loads it automatically; consumers do not download a separate binary.

## One-time publisher setup

1. Register at https://central.sonatype.com and verify the `com.orchiddb` namespace using ownership of `orchiddb.com`. Use the exact DNS TXT record supplied by Sonatype; no record can be provisioned until that verification value is issued.
2. Generate a Central **user token**. Store its username/password in the Java GitHub repository's `maven-central` environment as `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.
3. Configure an existing release-signing key or create one under your organization's key policy. Publish its public key to a keyserver supported by Central. Store the ASCII-armored secret key as `MAVEN_GPG_PRIVATE_KEY` and its passphrase as `MAVEN_GPG_PASSPHRASE` in that environment. Do not put secrets in source or command-line arguments.
4. Configure the `maven-central` environment for the desired maintainers/release protection. The pipeline has read-only GitHub contents permissions; Central credentials are only exposed to the staging job after all native jobs succeed.

The repository and workflow are separate from the Central account. Account verification, tokens, signing key and any environment rules are prerequisites, not something adding a POM silently completes.

## Prepare a version

1. Run `./scripts/build-gremlin.sh` and `./scripts/check-dependencies.sh` locally.
2. Ensure `native/CORE_REVISION` is the tested, pushed 40-character Rust core commit. Keep the native lockfile aligned. Release CI checks out this exact commit.
3. Set a non-SNAPSHOT version across the parent and both children using `mvn -Pgremlin versions:set -DnewVersion=0.1.0 -DprocessAllModules=true -DgenerateBackupPoms=false` (replace the version with your chosen release).
4. Commit the version change and create/push the matching tag, for example `v0.1.0`. The workflow requires an existing tag and verifies that its commit and Maven version match. It does not create or rewrite tags.
5. Run **Stage Java release on Maven Central** in GitHub Actions, entering that tag.

The workflow is configured to build and package four native binaries with a pinned Rust toolchain. It verifies artifact metadata, builds sources/Javadocs, signs all release artifacts, and submits through Sonatype's Central publishing plugin. Run integration and classpath-loading tests locally before release; the workflow does not run them. The published 0.1.0 release used locally built and tested macOS ARM64 artifacts, rather than this four-platform build.

After validation, review the deployment at https://central.sonatype.com/publishing/deployments and click Publish. Central releases are immutable; if validation or a native platform fails, fix and retest before publishing. A failed upload is not evidence that nothing was staged: inspect the portal before retrying.

## Consumer setup

Use the same version for both dependencies and select the classifier matching the **JVM**, not just the host machine. For the published 0.1.0 Apple Silicon package:

```xml
<dependency>
  <groupId>com.orchiddb</groupId><artifactId>orchiddb-java</artifactId><version>0.1.0</version>
</dependency>
<dependency>
  <groupId>com.orchiddb</groupId><artifactId>orchiddb-java</artifactId><version>0.1.0</version>
  <classifier>macos-aarch64</classifier><scope>runtime</scope>
</dependency>
```

```java
var compiler = io.orchiddb.NativeSqlCompiler.load();
```

`load()` extracts the installed native JAR after version/revision/checksum checks. It makes no network request and requires no manual binary installation. For source development only, `-Dorchiddb.native.path=/absolute/path/to/compiler` overrides classpath extraction, or call `load(Path)` explicitly. Add `com.orchiddb:orchiddb-gremlin:0.1.0` only when needed.

## Local packaging and repository verification

Package a locally built compiler (development binaries are for local testing, not release publication):

```sh
python3 scripts/package-native.py --platform macos-aarch64 \
  --library native/target/debug/liborchiddb_java.dylib \
  --version 0.1.0 --output target/native-artifacts/macos-aarch64.jar
python3 scripts/test-native-package.py \
  --jar target/native-artifacts/macos-aarch64.jar --version 0.1.0
mvn -Pgremlin,native-local -Dnative.classifier=macos-aarch64 \
  -DskipTests -DaltDeploymentRepository=local-check::file:///tmp/orchiddb-maven-check deploy
```

This tests ordinary Maven publication without Central credentials. It does not certify the unbuilt platforms. `native-release` attaches all four classifier JARs and fails if any file is absent. CI's `verify-native-artifacts.py` also checks each artifact's version, pinned core revision, embedded license and checksum before any signing/upload.

## References

- [Central publisher requirements](https://central.sonatype.org/publish/requirements/)
- [Central namespace verification](https://central.sonatype.org/register/namespace/)
- [Central Maven publishing plugin](https://central.sonatype.org/publish/publish-portal-maven/)
- [Maven signing configuration](https://maven.apache.org/plugins/maven-gpg-plugin/usage.html)
