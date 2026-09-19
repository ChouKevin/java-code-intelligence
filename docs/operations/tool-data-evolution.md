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
   sealed-generation reuse partial index, generation identity uniqueness,
   active-job uniqueness, and Git ordinal/ID indexes. There is no TTL index and
   no unique content-SHA index.
5. Deploy the schema-3 Indexer. Rebuild every approved repository current
   generation so each sealed manifest has projection version 3 plus a v1
   analysis fingerprint and evidence. A pre-cutover manifest is never reused.
6. Reprepare standalone Git evidence at version 2 as needed. Create new review
   manifests only from the rebuilt sealed generations and their explicit Git
   evidence graph.
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
