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
- Per-root declaration/symbol proofs are fail-honest limitations until Task 4 feeds the bound semantic service through each extracted root. No synthetic root success is persisted.

## Post-commit image smoke correction

Controller verification reported that the first smoke-script execution exited with `sh: Syntax error: Missing '))'`: `response=$((` accidentally started arithmetic expansion. The RED was the controller-run actual smoke against `semantic-indexer:review-task3` (exit 2). The corrected command substitution is `response=$( (`; `sh -n scripts/smoke-jdtls-image.sh` then exited 0.

The next actual smoke exposed a genuine restricted-child launcher failure: Equinox attempted to write logs and native-extraction state into immutable `/opt/jdtls/config_linux`. The production factory now copies that configuration into each lease's contained `workspaceData/configuration`, changes all runtime/configuration paths to UID 10001 only in `LINUX_UID` mode, and launches with that owned configuration directory. The smoke fixture likewise creates and chowns a disposable `/data/jdtls/smoke/configuration`.

After rebuilding the image, the following completed with exit 0:

```sh
bash scripts/smoke-jdtls-image.sh semantic-indexer:review-task3
```

Its restricted JDT launch completed the initialize/shutdown exchange while preserving the UID 10001, bounded-capability, and synthetic-parent-secret checks. The focused manager/process command was rerun after the factory change: `72` tests, `0` failures, `0` errors, `BUILD SUCCESS`.
