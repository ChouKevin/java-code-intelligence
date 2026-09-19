# Task 4 report: truthful semantic export

## RED / GREEN

- **RED:** `GenerationValidatorIT` initially rejected the test seed with `UNSUPPORTED_SCHEMA` after semantic-evidence persistence raised the schema contract. The seed was updated to use `IndexSchemaContract.SCHEMA_VERSION`; the focused freeze test then passed.
- **GREEN:** `JdtLsRepositoryIndexExporterSemanticSessionTest` proves that a mixed JDT response preserves one resolved and one unresolved invocation, and that export fails when no semantic invocation readiness proof can be made. `mvn -pl semantic-indexer -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JdtLsRepositoryIndexExporterSemanticSessionTest test` completed with 2 tests, 0 failures.
- **GREEN:** `mvn -pl semantic-indexer -am -Pmongo-it -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GenerationValidatorIT,FullIndexPublicationIT test` completed with 39 tests, 0 failures and 0 errors. This includes missing and mismatched semantic-evidence rejection and sealing/publication coverage.

## Changed files

- Added `RepositoryIndexExport`, changing production export to carry semantic readiness evidence with batches.
- Bound production export to `PreparedAnalysis`; semantic calls retain JDT status and unresolved calls are projected as unresolved rather than syntax-resolved.
- Removed singleton registration and manager-backed auto-start from `Lsp4jJavaSemanticService`; production construction requires a snapshot-bound JDT session.
- Persisted analysis fingerprint, effective input document, and semantic evidence into writing manifests; validation rejects absent, mismatched, syntactic-only, malformed, or inconsistent evidence and includes it in the manifest identity digest.
- Restricted reuse to matching semantic-analysis fingerprints.
- Requested JDT LS `test` classpaths when inspecting effective environment, so recorded JDT inputs cover test, provided, and runtime dependency closure rather than launcher runtime classpath alone.
- Updated build scope wiring and affected test fixtures for the snapshot-bound semantic service; added evidence rejection coverage.

## Self-review

- Checked the exporter does not use syntax targets after an unresolved or ambiguous JDT call response.
- Checked resolution accounting invariant (`attempted = resolved + unresolved + ambiguous`) and external-call subset are validated before sealing.
- Checked analysis fingerprint/evidence are both persisted before batch writes and are included in manifest digest material.
- Checked incremental parent selection requires the exact persisted fingerprint, preventing reuse from an unrelated current parent.
- Scoped verification was used as requested; formatters, linters, and project-wide suites were not run.
