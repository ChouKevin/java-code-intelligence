# Tool projection data evolution

## Codebase and semantic review schema 4

The semantic review release is one coordinated persisted-contract cutover. The
following versions are one compatibility boundary:

| Contract | Required version |
| --- | ---: |
| Global persisted schema | 4 |
| `SOURCES` projection | 4 |
| `SYMBOLS` projection | 4 |
| `RELATIONS` projection | 4 |
| `ENTRY_POINTS` projection | 4 |
| `SEARCH` projection | 4 |
| Semantic analysis evidence | 1 |
| Review manifest | 2 |
| Git evidence | 3 |
| Persisted job | 3 |

Do not decode an earlier generation as schema 4. Missing analysis evidence,
source membership, source policy, guide state, coverage, or structure makes a
generation incompatible; none has an old-schema default. This release has no
compatibility decoder, handwritten migration registry, or backward-compatible
reader.

Release and rebuild in this exact order:

1. Land the model contracts, Indexer writers/validators, Query readers, and the
   schema/index catalogue as one release set; do not expose a partial writer or
   reader.
2. Close new admissions, let active jobs reach their normal terminal recovery
   boundary, then stop the old Indexer before maintenance. Do not mix old active
   jobs with schema-4 writes.
3. Back up coherent repository pointers, generation manifests and payloads,
   jobs, Git evidence, and any review graph that must be retained.
4. Run the schema-maintenance bootstrap for schema 4 and verify its named
   indexes: the unique review `(repoId, reviewId)` index, review owner lookup,
   sealed-generation reuse partial index on the persisted flat
   `analysisFingerprint` field, generation identity uniqueness, active-job
   uniqueness, and Git ordinal/ID indexes. Preserve the existing global semantic
   source-artifact unique indexes on `sourceArtifactId` and `contentHash`.
   There is no TTL index or new unique commit-SHA constraint on generations,
   reviews, or Git snapshots; Git snapshot deduplication is not introduced.
5. Deploy the schema-4 Indexer. Rebuild every approved repository current
   generation with projection version 4, v1 analysis fingerprint/evidence, and
   the sealed source contract below. A pre-cutover manifest is never reused.
6. Reprepare Git evidence at version 3. Catalog and history manifests must
   persist `scope: STANDALONE`, without a `reviewId`; missing scope is invalid.
   Non-empty-tree snapshots bind an exact source generation, not just a commit.
   Reprepare incompatible evidence rather than patching immutable manifests.
   Create new review manifests only from rebuilt sealed generations and their
   explicit BEFORE/AFTER Git evidence graph.
7. Verify rebuilt manifests, generation identity digests, analysis evidence,
   exact source memberships, and named indexes. Only then deploy Query and
   reopen admissions.

The Indexer dispatcher never creates or repairs schema. If a required index is
missing or a same-named index has a conflicting definition, it stops before
generation work and directs operators to schema maintenance. This works on
standalone MongoDB; publication remains an expected-parent, single-document
repository-pointer update and does not require transactions or a replica set.

A rebuild of the same source revision writes a new immutable generation and
moves the repository pointer only after validation and sealing. It never updates
the previous generation. Rollback means repointing to an already sealed,
compatible generation; schema rollback remains a separate, non-destructive
maintenance operation. Retain a READY review's generations, snapshots, and
comparison as a graph; cleanup is manual and must first account for active jobs
and review references.

## Sealed source membership

Schema-4 generation manifests require `sourceSnapshot`, `sourcePolicy`,
`projectGuide`, `coverage`, and `structure` before sealing. `sourceSnapshot`
contains the exact snapshot ID, revision, policy fingerprint, and content digest.
Every non-EMPTY_TREE snapshot, including one with zero admitted files, stores
`sourceGenerationId`. A same-SHA rebuild may have equivalent content but remains
a distinct immutable generation; readers never pick a newer or merely equivalent
generation to repair a missing binding.

Source publication admits selected Java and mapper-root XML from the prepared
import plan, plus at most one explicitly configured regular Markdown guide.
Configuration files, unrelated text/XML, symlinks, and excluded rename endpoints
are not source or patch evidence. Both sides of a rename must be eligible.
Snapshots are prepared before semantic sealing and the expected-parent current
pointer update. Failure does not replace the previous current generation.

Guide states are `DISABLED`, `ABSENT`, `INVALID`, and `AVAILABLE`. Only AVAILABLE
has a readable path, digest, imported revision, and author provenance. Invalid
guide content does not prevent code publication; storage or membership corruption
is not treated as an invalid optional document. Guide provenance `analyzedRevision`
remains distinct from guide membership `importedRevision` (the generation's source
revision), and freshness is always `NOT_VERIFIED`. A guide is source evidence,
never a semantic fact.

The framework-neutral source codec writes optional fields by omission, not as
Java `Optional` objects. Policy paths and guide identities are scalar strings;
provenance `generatedAt` is an ISO Instant string so sub-millisecond precision is
not lost. Decoders require the current field types and state invariants.
Coverage and structure are sealed objective summaries, not model conclusions.
`structure.packageCounts` counts distinct admitted source files per namespace,
including mapper XML, not classes or methods. Package names remain literal keys,
including dots and the empty default-package name; do not escape or rewrite them.


## Operational release controls

Use a dedicated schema-maintenance Mongo identity only for the bootstrap command:

```bash
java -jar semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar \
  --semantic.schema-bootstrap=true
```

It is not a runtime worker. Runtime Indexer uses the writer identity; Query uses
a separate reader identity and must not receive schema-maintenance, writer, Git,
checkout, source, or JDT permissions. Close admissions before step 2 and reopen
them only after Query is deployed against verified compatible data.

The backup in step 3 is coherent only when it includes repository pointers, index
jobs, generation manifests and every referenced projection/source payload, Git
evidence, review manifests, and their referenced generations/comparison/snapshots.
Restore and cleanup preserve a READY review as that whole graph. Before manual
cleanup, check active jobs and every review reference; there is no TTL, automatic
GC, or safe single-member deletion.

Each Git comparison continues to write its own eligible snapshot text. A semantic
generation reused for BEFORE or AFTER does not deduplicate the comparison's previous or
current snapshot. Retention capacity must account for that duplication until a
separately designed storage contract changes it.

## Analyzer policy changes within schema 4

The Indexer hashes the explicit analyzer policy `semantic-indexer-analysis:1`
into `analyzerDigest`. Advance this policy version when source planning,
extraction, or semantic interpretation changes, even if persisted document
shapes do not. A Java implementation class name is not an analyzer version.

Generation reuse and incremental-parent selection require the resulting exact
analysis fingerprint. Generations produced with the earlier unversioned
class-name identity are therefore not reused by the corrected analyzer.
Rebuild affected current generations through ordinary indexing and prepare new
reviews; never rewrite the fingerprints or payloads of immutable old reviews.
An analyzer-policy change alone does not require schema bootstrap or a volume
reset. Persisted shape changes still follow the coordinated cutover above.

The corrected planner includes supported mapper XML in imported production
resource roots, while JDT declaration proof applies to Java-bearing roots.
Before sealing, validation compares the complete prepared source inventory,
including source artifact identities, with persisted outputs rather than
inferring completeness from whichever files happened to be exported.
