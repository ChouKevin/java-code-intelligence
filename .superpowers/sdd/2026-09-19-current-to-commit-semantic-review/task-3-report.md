# Task 3 report — effective analysis inputs and isolated workspaces

## Scope delivered

- Added `AnalysisTarget`, `PreparedAnalysis`, preparation/reuse contracts, a concrete lease-backed preparation, and a conservative re-attestation reuse verifier.
- Added validated server-controlled `AnalysisWorkspaceKey` and `WorkspaceLease`; `DefaultJdtWorkspaceManager.acquire` creates a fresh contained `repo/revision/job/stage/lease-*` directory, starts an independent session, and removes only that owned lease directory after stopping it.
- Added the bound `Lsp4jJavaSemanticService(RepositorySnapshot, JdtWorkspaceSession)` constructor. Bound calls reject a different snapshot and reject a closed session; the existing manager path remains solely for unmigrated Task 4 callers.
- Added post-import `JdtLsEffectiveEnvironmentInspector`. It invokes `java.project.getAll`, `java.project.getSettings`, and `java.project.getClasspaths`, rejects malformed/escaped/project-mismatched data, preserves ordered classpath/modulepath entries, records content digests rather than host locations, distinguishes Maven profile applicability through the imported nature IDs, and obtains Maven profiles only for Maven projects.
- The actual JDT 1.50 dispatcher accepts its `ClasspathOptions` only as a JSON element/string; LSP4J delivers a Java `Map` as a plain map which JDT’s `JSONUtility.toModel` turns into `null`. The inspector therefore sends the exact wire JSON string `{"scope":"runtime"}` as the second `getClasspaths` argument. This is a controller-confirmed correction to the brief’s `Map.of` snippet, not an absent-options fallback.
- Recorded the actual `java/buildWorkspace` status. `FAILED`, `CANCELLED`, and a missing status fail readiness; `WITH_ERROR` is preserved as an evidence limitation. Nonempty included roots without per-root declaration proof are recorded as `ROOT_SYMBOL_PROOF_UNAVAILABLE`, never asserted successful.
- Added explicit runtime process properties: Java executable, isolation mode, analysis UID/GID/home. Production configuration and the indexer image use `LINUX_UID`, UID/GID 10001, `setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all`, `util-linux`, and a scrubbed child environment. The launcher has no privileged fallback.
- Extended image smoke coverage to check the account, capability bounding, synthetic parent-secret denial, and actual restricted JDT LS initialize/shutdown exchange.
- Added an actual real-JDT fixture: it creates a temporary Git Maven repository with a custom production source root and a compiled external deterministic JAR; a fresh B lease after committing B observes changed artifact bytes and B’s exact revision.
- Added effective source-root filtering to planning and a syntax extraction overload that accepts imported source roots/compiler options; the original syntax API remains for unmigrated callers.

## RED / GREEN evidence

### RED

The first real-JDT inspection attempt executed the new post-import command sequence and failed at `java.project.getClasspaths` with the server response:

```
Cannot read field "scope" because "options" is null
```

This was a behavioral protocol failure, not a compiler or fixture prerequisite failure. It exposed that JDT LS 1.50’s `JSONUtility.toModel` does not accept LSP4J’s decoded Java `Map` for `ClasspathOptions`.

A second real-JDT attempt reached Maven selected-profile decoding and failed strictly because the effective M2E response was a string rather than the assumed list. The inspector now accepts the two actual JDT/M2E response shapes (CSV string or JSON list) and rejects other shapes.

### GREEN

The final real-JDT fixture completed A and B using independent leases. It proved a changed external dependency digest, preserved B’s committed revision, included the custom production root in the plan, and persisted path-free artifact identifiers.

## Commands and outputs

All commands used Maven 3.9.11 from `.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn` with Java 21 and `JDTLS_HOME=/opt/jdtls`.

1. Focused manager/process proof:

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=DefaultJdtWorkspaceManagerTest,JdtLsProcessFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Final output: `Tests run: 72, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS`.

2. Exact real-JDT command:

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it -Dtest=EffectiveEnvironmentJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test
```

Final output: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS`.

Docker image verification is recorded below; no formatter, linter, project-wide suite, or user repository was run.

## Files changed

- `Dockerfile.indexer`
- `scripts/smoke-jdtls-image.sh`
- `semantic-indexer/src/main/resources/application.yml`
- `semantic-indexer/src/main/java/com/java/semantic/config/JdtLsProperties.java`
- `semantic-indexer/src/main/java/com/java/semantic/indexer/analysis/{AnalysisTarget,PreparedAnalysis,RepositoryAnalysisPreparation,AnalysisReuseVerifier,DefaultRepositoryAnalysisPreparation,ConservativeAnalysisReuseVerifier}.java`
- `semantic-indexer/src/main/java/com/java/semantic/indexer/build/FullIndexPlanner.java`
- `semantic-indexer/src/main/java/com/java/semantic/semantic/adapter/jdtls/{AnalysisWorkspaceKey,WorkspaceLease,JdtLsEffectiveEnvironmentInspector,JdtWorkspaceManager,DefaultJdtWorkspaceManager,JdtWorkspaceSession,JdtLsReadinessProbe,JdtLsBuildWorkspaceStatus,JdtLsProcessFactory,Lsp4jJavaSemanticService}.java`
- `semantic-indexer/src/main/java/com/java/semantic/syntax/adapter/jdt/{JdtAstParser,JdtSyntaxExtractionService}.java`
- `semantic-indexer/src/test/java/com/java/semantic/semantic/adapter/jdtls/{DefaultJdtWorkspaceManagerTest,JdtLsProcessFactoryTest,EffectiveEnvironmentJdtLsIT}.java`

## Self-review

- Lease data directories are normalized, checked as descendants of the configured data root, and deleted only from the exact owned lease path.
- Persisted paths are repository-relative; library logical IDs are Maven coordinates when derivable, otherwise content-addressed; persisted digests never include installation paths, credentials, or timestamps.
- The real fixture exercises a changed JAR’s bytes rather than relying on directory-name differences.
- Maven-only selected profiles are never requested from a non-Maven project; non-Maven applicability is explicitly represented in effective options.
- `LOCAL_TRUSTED` remains explicit test/development configuration. Production image configuration is `LINUX_UID`; launch failure propagates.
- Java additions avoid `var` and direct null comparison, and lease/session ownership is explicit.

## Concerns / intentional limits

- Task 4 still owns the complete production caller cutover. The legacy manager-based semantic service constructor, singleton wiring, and auto-start path remain deliberately for unmigrated callers as prescribed.
- The image smoke script was changed and executed after a targeted image build; see the post-commit correction evidence below.
- Each included nonempty source root is now proved by a leased, snapshot-bound JDT workspace-symbol declaration query. A missing declaration fails preparation rather than persisting a successful empty proof.

## Post-commit image smoke correction

Controller verification reported that the first smoke-script execution exited with `sh: Syntax error: Missing '))'`: `response=$((` accidentally started arithmetic expansion. The RED was the controller-run actual smoke against `semantic-indexer:review-task3` (exit 2). The corrected command substitution is `response=$( (`; `sh -n scripts/smoke-jdtls-image.sh` then exited 0.

The next actual smoke exposed a genuine restricted-child launcher failure: Equinox attempted to write logs and native-extraction state into immutable `/opt/jdtls/config_linux`. The production factory now copies that configuration into each lease's contained `workspaceData/configuration`, changes all runtime/configuration paths to UID 10001 only in `LINUX_UID` mode, and launches with that owned configuration directory. The smoke fixture likewise creates and chowns a disposable `/data/jdtls/smoke/configuration`.

After rebuilding the image, the following completed with exit 0:

```sh
bash scripts/smoke-jdtls-image.sh semantic-indexer:review-task3
```

Its restricted JDT launch completed the initialize/shutdown exchange while preserving the UID 10001, bounded-capability, and synthetic-parent-secret checks. The focused manager/process command was rerun after the factory change: `72` tests, `0` failures, `0` errors, `BUILD SUCCESS`.

## Re-review correction evidence

The inspector now rejects omitted `org.eclipse.jdt.ls.core.sourcePaths`, `classpaths`, and `modulepaths`; explicitly present empty lists remain valid. It requests the complete local JDT Core compiler-option key set and carries the attested selected roots and effective compiler options in `FullIndexPlan`, which the exporter supplies to active AST extraction.

Only paths reported as JDT classpath `CPE_PROJECT` outputs are recorded as `PROJECT_EDGE`; all other repository-contained artifacts are byte-digested as ordinary classpath/modulepath entries.

The per-root declaration proof is performed through `Lsp4jJavaSemanticService(snapshot, session)` and therefore rejects a closed lease or a mismatched snapshot before the JDT request can execute.

After these changes, focused verification completed:

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=DefaultJdtWorkspaceManagerTest,JdtLsProcessFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 72, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS

JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it -Dtest=EffectiveEnvironmentJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS
```

## Second re-review focused evidence

`requiredStringList` now rejects omitted and JSON-null values and accepts only actual lists; explicit empty lists remain valid. The focused contract test exercises each required source-path, classpath, and modulepath field for missing, null, wrong-type, and empty-list behavior.

The exporter again validates all planned source contents after syntax extraction, preserving the mutation/TOCTOU boundary.

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=JdtLsEffectiveEnvironmentInspectorTest,JdtLsRepositoryIndexExporterSemanticSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 6, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS
```

## Final integration correction evidence

### RED

The focused `IndexBuildService` regression was added after introducing its prepared-analysis constructor boundary. Before the service consumed that boundary, the test failed because the active build never invoked the supplied preparation:

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=IndexBuildServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
# FAIL: exports_the_prepared_plan_without_rediscovering_checkout_sources
# Wanted but not invoked: repositoryAnalysisPreparation.prepare(...)
# Tests run: 3, Failures: 1, Errors: 0
```

The existing plan-aware exporter behavior was characterized by the new custom-root/source-8 `record` assertion rather than a second artificial RED: its focused test was already green before this correction. The effective-environment fixture was expanded from one Maven module/external JAR to a two-module reactor plus an in-repository JAR; it passed after the project-output mapping was made explicit.

### GREEN

`IndexBuildService` now acquires `PreparedAnalysis` for `CODEBASE`, exports its exact plan while the lease remains owned, and closes it afterward. The production scope factory receives the same preparation from Spring. A plan records whether its roots/options came from imported inputs, so an explicit empty imported root inventory is never interpreted as an invitation to rediscover roots; incremental subsets preserve that flag, roots, and compiler options.

The inspector first records every imported project’s default output and CPE_SOURCE per-entry outputs, then only turns a runtime entry into `PROJECT_EDGE` when that entry belongs to a CPE_PROJECT reference to one of those imported projects. An in-repository system JAR consequently remains a `CLASSPATH` artifact with SHA-256 content digest and byte length.

The real-JDT fixture now creates `api` and `application` Maven modules, a repository-contained `libraries/fixture.jar`, and A/B API/source changes. It asserts `api/target/classes` is a revision-tied project edge, the JAR is not an edge and changes digest, and `second.semanticService()` resolves `UseApi.bOnly(7)` to the B-only local `example.api.Api.bOnly(int): int` target.

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=IndexBuildServiceTest,JdtLsRepositoryIndexExporterSemanticSessionTest,RepositoryBuildRunnerSpringWiringTest,JdtLsEffectiveEnvironmentInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 14, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS

JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it -Dtest=EffectiveEnvironmentJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS
```

The Maven test lifecycle compiled all affected production and test sources; no formatter, linter, project-wide suite, Docker, or Mongo suite was run.

### Files changed

- `.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/task-3-report.md`
- `semantic-indexer/src/main/java/com/java/semantic/indexer/{build/FullIndexPlan.java,build/IncrementalGenerationBuilder.java,build/IndexBuildService.java,build/JdtLsRepositoryIndexExporter.java,build/RepositoryBuildScopeFactory.java,config/IndexerBuildConfiguration.java}`
- `semantic-indexer/src/main/java/com/java/semantic/semantic/adapter/jdtls/JdtLsEffectiveEnvironmentInspector.java`
- `semantic-indexer/src/test/java/com/java/semantic/indexer/{build/IndexBuildServiceTest.java,build/JdtLsRepositoryIndexExporterSemanticSessionTest.java,build/RepositoryBuildRunnerSpringWiringTest.java,job/DispatchedBuildIT.java}`
- `semantic-indexer/src/test/java/com/java/semantic/semantic/adapter/jdtls/EffectiveEnvironmentJdtLsIT.java`

### Self-review

- The runtime edge decision is exact-output equality after a declared CPE_PROJECT reference; it no longer classifies arbitrary repository-contained paths as edges.
- Non-project files and directories continue through `digestPath`/`byteLength`, including deterministic directory inventory handling.
- The active production path uses the prepared plan; only intentionally legacy two-argument plans retain root discovery for unmigrated callers. The Task 4 manager constructor/singleton remains untouched.
- Prepared leases span export and close in `finally`; post-extraction source revalidation remains in the exporter.

## Bound prepared-export correction

### RED

The prepared-plan test was extended to require the exporter overload receiving `PreparedAnalysis.semanticService()`. Before the correction, `IndexBuildService` invoked the four-argument exporter path instead:

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=IndexBuildServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
# FAIL: exports_the_prepared_plan_without_rediscovering_checkout_sources
# Actual invocation: repositoryIndexExporter.export(..., preparedPlan)
# Wanted invocation: repositoryIndexExporter.export(..., preparedPlan, boundSemanticService)
# Tests run: 3, Failures: 1, Errors: 0
```

### GREEN

`IndexBuildService` now supplies `PreparedAnalysis.semanticService()` to prepared exports while that analysis lease remains open. `JdtLsRepositoryIndexExporter` constructs its semantic resolver from that supplied service for the complete workspace probe and call-target-resolution lifecycle; its legacy four-argument path remains for unmigrated Task 4 callers.

The focused exporter test creates a failing constructor-injected legacy service and a resolving supplied service. A prepared export completes with the supplied service’s workspace check and zero legacy-service checks.

```sh
JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Dtest=IndexBuildServiceTest,JdtLsRepositoryIndexExporterSemanticSessionTest -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS

JDTLS_HOME=/opt/jdtls .superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it -Dtest=EffectiveEnvironmentJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS
```

### Files changed

- `.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/task-3-report.md`
- `semantic-indexer/src/main/java/com/java/semantic/indexer/build/{RepositoryIndexExporter.java,IndexBuildService.java,JdtLsRepositoryIndexExporter.java}`
- `semantic-indexer/src/test/java/com/java/semantic/indexer/build/{IndexBuildServiceTest.java,JdtLsRepositoryIndexExporterSemanticSessionTest.java}`

### Self-review

- The prepared path selects exactly one bound service, uses it for all exporter semantic calls, and closes its lease only after export returns or fails.
- The injected legacy JDT service remains available only to the legacy exporter overload, preserving Task 4’s assigned cutover boundary.
