# Task 2 report: versioned semantic review evidence contracts

## Scope

Implemented the framework-neutral versioned analysis/review/Git identity model,
schema-3 catalogue and indexes, compatible fixture projection construction, and
the schema-3 cutover/rebuild procedure. This task does not implement review
admission, review preparation, Query review selection, or review/Git BSON
mapping owned by later tasks.

## RED evidence

### Canonical fingerprint and READY membership

After the permitted minimal declaration preflight, this focused command produced
behavioral failures rather than missing-symbol/compiler failures:

```bash
.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn \
  --batch-mode --no-transfer-progress -pl semantic-model \
  -Dtest=AnalysisFingerprintTest,IndexDocumentContractTest test
```

Observed failures:

- `AnalysisFingerprintTest`: the intentionally placeholder fingerprint returned
a single all-zero digest for ordered and reversed classpaths.
- `IndexDocumentContractTest`: an invalid READY manifest with A revision
  different from the captured baseline was accepted.

Both failures were behavioral assertions: identity collision and invalid READY
membership acceptance.

The initial Mongo invocation could not reach test execution because a concurrent
Task 1 facade migration left `DispatchedGitEvidenceIT` with an obsolete
constructor. Task 1 committed `5778624` to migrate that fixture; no Task 2 files
overlapped. To prove the Mongo test itself fails for the intended behavioral
reason, the final review index was temporarily mutated from unique to non-unique
and this command failed with the duplicate insertion accepted at
`IndexSchemaBootstrapIT:76`:

```bash
.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn \
  --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pmongo-it \
  -Dtest=IndexSchemaBootstrapIT#rejects_duplicate_review_ids_within_a_repository_but_allows_the_same_review_id_in_another_repository \
  -Dsurefire.failIfNoSpecifiedTests=false verify
```

The unique definition was immediately restored before the GREEN run.

## GREEN evidence

Focused model contracts:

```bash
.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn \
  --batch-mode --no-transfer-progress -pl semantic-model \
  -Dtest=AnalysisFingerprintTest,IndexDocumentContractTest test
```

Result: `Tests run: 24, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`.

Focused Mongo schema contract:

```bash
.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn \
  --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pmongo-it \
  -Dtest=IndexSchemaBootstrapIT -Dsurefire.failIfNoSpecifiedTests=false verify
```

Result: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`; reactor `BUILD SUCCESS`.

Testcontainers emitted expected connection-reset messages while disposable Mongo
containers were torn down, and Mockito/JDK emitted dynamic-agent warnings. These
did not produce test failures or Maven build failures.

## Files changed

### Model contracts

- `semantic-model/src/main/java/com/java/semantic/model/index/AnalysisInputs.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/AnalysisFingerprint.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/SemanticAnalysisEvidence.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/SealedGeneration.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/GenerationManifestDocument.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/IndexCollections.java`
- `semantic-model/src/main/java/com/java/semantic/model/index/IndexSchemaContract.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewId.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewSide.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewState.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewComparisonType.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/CapturedReviewBaseline.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewEndpoint.java`
- `semantic-model/src/main/java/com/java/semantic/model/review/ReviewManifestDocument.java`
- `semantic-model/src/main/java/com/java/semantic/model/git/GitPublicationScope.java`
- `semantic-model/src/main/java/com/java/semantic/model/git/GitEvidenceOwnership.java`
- `semantic-model/src/main/java/com/java/semantic/model/git/GitCatalogManifest.java`
- `semantic-model/src/main/java/com/java/semantic/model/git/GitHistoryManifest.java`

### Directly required writer/test fixtures

- `semantic-indexer/src/main/java/com/java/semantic/indexer/store/GitEvidencePublicationStore.java`
- `semantic-indexer/src/test/java/com/java/semantic/indexer/store/IndexSchemaBootstrapIT.java`
- `semantic-indexer/src/test/java/com/java/semantic/indexer/build/GenerationValidatorIT.java`
- `semantic-indexer/src/test/java/com/java/semantic/indexer/job/GitEvidenceJobHandlerTest.java`
- `semantic-model/src/test/java/com/java/semantic/model/AnalysisFingerprintTest.java`
- `semantic-model/src/test/java/com/java/semantic/model/IndexDocumentContractTest.java`
- `semantic-query/src/test/java/com/java/semantic/query/application/CurrentQueryContractIT.java`
- `semantic-query/src/test/java/com/java/semantic/query/application/PublishedMongoITSupport.java`
- `semantic-query/src/test/java/com/java/semantic/query/application/SelectedGenerationReadContractIT.java`

### Operations

- `docs/operations/tool-data-evolution.md`

## Self-review

- `AnalysisFingerprint` writes each canonical UTF-8 scalar with a byte-length
  prefix, sorts only compiler-option maps and project/root sets, and preserves
  selected-profile, classpath, and modulepath sequence. Artifact logical ID,
  ordinal, content digest, and byte length participate in the digest.
- All new model values make defensive `List.copyOf`/`Map.copyOf` copies and use
  the shared SHA-256/text validation conventions. They contain no Mongo
  `Document` or framework type.
- `SealedGeneration` requires evidence with the matching fingerprint; a sealed
  manifest requires it too. `AnalysisFingerprint` is a required manifest field,
  with no old-schema default or decoder.
- READY review membership requires both endpoints, comparison and publication
  time, forbids failure category, validates repository/revision and the captured
  A pointer identity, and allows equal SHA/generation only for identical sealed
  endpoint evidence/snapshot identities.
- Git ownership enforces standalone/no-review versus review/required-review ID;
  catalog and history records accept standalone ownership only. Version constants
  are centralized: schema/projections 3, analysis/review 1, Git/job 2.
- The schema catalogue retains generation, active-job, and Git ordinal/ID
  indexes; adds unique review, review owner, and sealed reuse indexes; and removes
the unique content-SHA index. It defines no TTL index.
- Compatible fixture manifests now derive projection versions from
  `IndexSchemaContract.requiredProjectionVersions()`; deliberately incompatible
  fixtures retain their mismatches.
- `git diff --check` completed with no whitespace errors. New Java uses explicit
  local types and no new direct null comparisons.

## Concerns / later-task boundary

Concrete schema-3 generation BSON writing, validation sealing, and identity-digest
calculation including analysis evidence are owned by Task 4. Review admission,
preparation, READY publication BSON, comparison/snapshot version-2 BSON, and
strict review/Git read decoding are intentionally left to their assigned later
tasks. No compatibility path was added for pre-schema-3 data.

## Fix round 1: analysis evidence and local identity boundaries

### Findings addressed

- A `WRITING` manifest whose `validationResult` was `VALID` could omit analysis
  evidence. Evidence is now mandatory immediately at `VALID` and remains
  mandatory for `SEALED_VALID`; present evidence must still match the required
  analysis fingerprint digest.
- Artifact logical IDs could contain local POSIX paths, Windows absolute paths,
  or local `file:` URIs and therefore enter the fingerprint. The model now
  rejects those local locations before canonicalization while retaining opaque
  logical identifiers such as Maven coordinates and project edges.
- Updated the stale `SEARCH v2` schema comment to `SEARCH v3`.

### RED

```bash
.superpowers/sdd/2026-09-19-current-to-commit-semantic-review/tools/apache-maven-3.9.11/bin/mvn \
  --batch-mode --no-transfer-progress -pl semantic-model \
  -Dtest=AnalysisFingerprintTest,IndexDocumentContractTest test
```

Observed behavioral failures:

- All three local artifact forms (`/home/...`, `C:\\Users\\...`, and
  `file:///home/...`) were accepted.
- A `WRITING` generation with `validationResult=VALID` and no analysis evidence
  was accepted.

The test suite reported 4 failures across 28 tests. The pre-existing mismatched
evidence assertion was retained and protects the digest-consistency invariant.

### GREEN

The same focused command passed after the model changes:

```text
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Fix-round self-review

- The new artifact guard is evaluated before `AnalysisFingerprint` can serialize
  `logicalId`; it rejects POSIX-rooted, backslash-rooted/UNC, drive-rooted, and
  `file:` local forms without changing valid logical identifier semantics.
- `validationResult.filter("VALID"::equals)` makes the validated boundary
  explicit while preserving ordinary WRITING staged optionality before
  validation. No persistence state abstraction or decoder was added.
- The implementation uses explicit Java types and no direct null comparison.
