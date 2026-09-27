# Tool projection data evolution

## Semantic review schema 3

The semantic review release is one coordinated persisted-contract cutover. The
following versions are one compatibility boundary:

| Contract | Required version |
| --- | ---: |
| Global persisted schema | 3 |
| `SOURCES` projection | 3 |
| `SYMBOLS` projection | 3 |
| `RELATIONS` projection | 3 |
| `ENTRY_POINTS` projection | 3 |
| `SEARCH` projection | 3 |
| Semantic analysis evidence | 1 |
| Review manifest | 1 |
| Git evidence | 2 |
| Persisted job | 2 |

Do not decode a version 2 generation as schema 3. In particular, an old or
missing analysis fingerprint/evidence is not an analysis environment and is
not eligible for reuse or review membership. This release has no compatibility
decoder, default value, handwritten migration registry, or backward-compatible
reader.

Release and rebuild in this exact order:

1. Land the model contracts, Indexer writers/validators, Query readers, and the
   schema/index catalogue as one release set; do not expose a partial writer or
   reader.
2. Drain new admissions, stop the old Indexer, and let its active jobs reach
   their normal terminal recovery boundary before maintenance. Do not mix old
   active jobs with schema-3 writes.
3. Back up coherent repository pointers, generation manifests and payloads,
   jobs, Git evidence, and any review graph that must be retained.
4. Run the schema-maintenance bootstrap for schema 3 and verify its named
   indexes: the unique review `(repoId, reviewId)` index, review owner lookup,
   sealed-generation reuse partial index on the persisted flat
   `analysisFingerprint` field, generation identity uniqueness, active-job
   uniqueness, and Git ordinal/ID indexes. Preserve the existing global semantic
   source-artifact unique indexes on `sourceArtifactId` and `contentHash`.
   There is no TTL index or new unique commit-SHA constraint on generations,
   reviews, or Git snapshots; Git snapshot deduplication is not introduced.
5. Deploy the schema-3 Indexer. Rebuild every approved repository current
   generation so each sealed manifest has projection version 3 plus a v1
   analysis fingerprint and evidence. A pre-cutover manifest is never reused.
6. Reprepare standalone Git evidence at version 2 during the cutover. Query
   rejects version 1 rather than inferring standalone ownership. Version-2
   catalog and history manifests must persist `scope: STANDALONE`, without a
   `reviewId`; a missing scope is invalid, not an implicit ownership default.
   Reprepare malformed intermediate evidence rather than patching immutable
   manifests. Create new review manifests only from the rebuilt sealed
   generations and their explicit Git evidence graph.
7. Verify the rebuilt manifests, generation identity digests, analysis evidence,
   and named indexes. Only then deploy the Query release and reopen admissions.

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
generation reused for A or B does not deduplicate the comparison's previous or
current snapshot. Retention capacity must account for that duplication until a
separately designed storage contract changes it.

## Analyzer policy changes within schema 3

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
