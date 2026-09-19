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

## Review follow-up

- Removed exporter compatibility constructors and throwing legacy overloads. Syntax projection tests use explicit test-only projection; prepared/context export is the only production path.
- Incremental assembly narrows a complete lease-bound preparation to the selected export plan, retaining complete attestation while exporting only reanalyzed sources.
- Sealing accepts only JDT `SUCCESS` or `WITH_ERROR` evidence status. Identity canonicalizes analysis document keys and unordered project/root/proof/limitation collections instead of BSON `toJson()`.
- `mvn -pl semantic-indexer -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=IndexBuildServiceTest,IndexProjectionContractTest,SemanticRelationProjectorFrameworkEvidenceTest,JdtLsRepositoryIndexExporterSemanticSessionTest test`: 16 tests, 0 failures/errors.
- `mvn -pl semantic-indexer -am -Pmongo-it -Dsurefire.failIfNoSpecifiedTests=false -Dtest=GenerationValidatorIT,FullIndexPublicationIT test`: 39 tests, 0 failures/errors.

### Real-JDT environment evidence

The real-JDT effective-environment integration passed with the installed language server:
`JDTLS_HOME=/opt/jdtls mvn -pl semantic-indexer -am -Pjdtls-it -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EffectiveEnvironmentJdtLsIT test`.
It completed with 1 test, 0 failures, and 0 errors. The fixture declares distinct runtime (`slf4j-api`), provided (`junit-jupiter-api`), and test (`assertj-core`) dependencies; assertions observed all three in persisted effective JDT classpath artifacts. The fixture also supplies a test source and asserts the current policy marks its root excluded with the explicit `test` reason, so it does not claim test-only binding is required for the production export roots.

- `GenerationValidatorCanonicalDigestTest` verifies that input/evidence project, root, proof, limitation, and map insertion-order permutations produce the same sealed digest (1 test, 0 failures/errors).

### Real-JDT binding proof

`JDTLS_HOME=/opt/jdtls mvn -pl semantic-indexer -am -Pjdtls-it -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EffectiveEnvironmentJdtLsIT test` completed with 1 test, 0 failures, and 0 errors. The fixture opens each source under the same `withDocumentUri` / `didOpen` / `didClose` lifecycle as the production adapter and uses an interior identifier position. Raw JDT hover identifies the runtime and provided imports as `org.slf4j.LoggerFactory` and `org.junit.jupiter.api.Assertions`, respectively, and the identical request against local `Api` identifies `example.api.Api`. These assertions are correlated with the distinct persisted runtime/provided artifact entries and the explicit excluded test root. JDT definition/type-definition returned empty for jar-backed types before hover; member-call export behavior remains honestly `UNRESOLVED` rather than treating hover as call resolution.

### Maven-scope binding labels

The real-JDT fixture now distinguishes four Maven-valid cases: default compile `slf4j-api` is JDT-bound through hover and present in the runtime closure; provided `junit-jupiter-api` is JDT-bound through hover; explicit runtime-only `commons-lang3` is attested only in the resolved runtime inventory; and test-only `assertj-core` is inventory-attested but has no binding claim because the test root is explicitly excluded by policy.
