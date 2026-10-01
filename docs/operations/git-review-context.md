# Git review context journey

Git evidence is a historical, Mongo-only Query capability. It is prepared by the
Indexer and stays readable by its returned immutable IDs after the remote branch
moves. It does not change a semantic generation pointer.

Run Indexer and Query with separate identities. The Indexer identity submits
through private `/index/**` or the Indexer's `/mcp`; the Query identity only reads
Query HTTP and the Query's separate `/mcp`.
Keep `semantic.query.git-evidence.allowed-repositories` empty until an
administrator has explicitly approved whole-repository source evidence. A
repository with a forbidden repository, package, class, or method rule remains
denied for every Git-evidence operation. Query has only Mongo read access; it
never clones, fetches, opens a checkout, or starts Indexer to serve Git evidence.

## Prepare immutable evidence

Drain admissions and settle active jobs before stopping the old Indexer. Replace
or clear only the approved old dataset, then bootstrap schema4 with maintenance
identity, deploy Indexer and rebuild/verify evidence before compatible Query.
See [tool-data-evolution.md](tool-data-evolution.md). This is a coordinated
rebuild cutover, not additive compatibility decoding.

Before each preparation, create and durably save a fresh canonical lowercase UUID
`requestId`. With an admin token and configured repository, submit the intent once
and poll its repository-scoped status until `phase` is `COMPLETE`:

```bash
curl -sS -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://indexer:8080/index/repositories/orders/metadata" \
  -d '{"requestId":"<saved-metadata-request-uuid>"}'
# Omitted branch uses the configured default; an explicit "branch" prepares that branch's history.
# Save jobId and poll the returned Location: /index/repositories/orders/jobs?jobId=<jobId>.
# On COMPLETE, save metadataResult.catalogId, historyId, headRevision and observedAt.

curl -sS -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://indexer:8080/index/repositories/orders/reviews" \
  -d '{"requestId":"<saved-review-request-uuid>","selection":{"kind":"RANGE","beforeRevision":"<lowercase-before-sha>","afterRevision":"<lowercase-after-sha>"}}'
# On COMPLETE/READY, save review.reviewId; Query get_context returns public contexts.
```

Each submission returns `202` only after persisting the original request and job.
Metadata performs one fetch, pins the catalog and selected branch's reachable
history to that observation, and updates `metadataPointer` only after both are
READY. Other catalog branches need their own refresh before they have prepared
history. Metadata runs no JDT and does not publish a semantic generation.
Separate `/git/refs`, `/git/history`, `/git/comparisons`, and `/jobs/{jobId}` routes
have been removed, not aliased.

If a response is lost, use
`GET /index/repositories/orders/jobs?requestId=<original-saved-uuid>`, not the latest
job. This also finds terminal jobs after later admissions or restart. Lookup takes
exactly one of jobId/requestId. `REQUEST_NOT_FOUND` leaves acceptance unknown and
permits lookup only, not resubmission; reusing a UUID is `REQUEST_ID_REUSED`.
An inspected failure needs an explicit new intent/new UUID, never an automatic
retry. Wait for COMPLETE rather than treating prepared IDs in a failed job as
publication. Recovery cannot publish an orphan READY metadata pair.

Query reads only READY evidence; an owned pending ID returns retryable
`GIT_EVIDENCE_NOT_READY`, while unknown or denied repositories/evidence return 404.
READY evidence and terminal request identities have no automatic TTL or garbage
collection; retention is an explicit operator responsibility.

The schema-4 release uses `gitEvidenceVersion: 3`. Apply the schema bootstrap
before Indexer publication, then rebuild/reprepare evidence as required by
[tool-data-evolution.md](tool-data-evolution.md). Earlier schema/evidence versions
are not decoded into this contract. Bootstrap is maintenance-only; runtime Indexer
uses its writer role and Query uses a separate reader role.

## Review-owned comparison evidence

Comparison preparation is review-owned; there is no standalone comparison
preparation route. Submit `requestId` with either a `COMMIT` SHA or explicit
`RANGE` before/after SHAs. The dispatcher prepares the direct comparison
and its available semantic endpoints without reading or publishing the mutable
current pointer. A root `COMMIT` compares the real empty tree to that commit:
it has no before semantic generation, but does have a previous snapshot of the
empty tree. The job's `review` status tracks `reviewId` and prepared
`comparisonId`, `previousSnapshotId`, and `currentSnapshotId`. These IDs are not
permission to read evidence before COMPLETE/READY.

Call `get_context` with repositoryId and `selector: {"kind":"REVIEW","reviewId":"…"}`.
Copy READY `comparisonContext` into `compare_revisions` and `get_file_diff`;
copy each present side's `context` into unified file/source/search/semantic tools.
Root before is explicitly `EMPTY_TREE`, with no BEFORE semantic context. Its ADD
patch uses the returned changeId; no omitted-previous or raw comparisonId input
is accepted. PREPARING/FAILED owner IDs cannot bypass the READY membership gate.
A direct before → after comparison is not a PR merge-base diff, even when
after is an ancestor or diverges. Each comparison stores its own eligible
snapshot text; semantic generation reuse does not deduplicate it.

## Read through HTTP or MCP

Give Query only Mongo read access and `SEMANTIC_QUERY_API_TOKEN`. Configure the
allowlist explicitly, for example:

```yaml
semantic:
  query:
    git-evidence:
      allowed-repositories: [orders]
```

Use returned contexts and opaque cursors unchanged. Branch discovery takes
repositoryId; history also requires the exact branch. Continuation cursors pin
their original metadata snapshot even after a refresh publishes a new pointer.
Unknown branch or unprepared history is not a fake empty collection. Public
callers do not supply catalog/history/snapshot IDs. Matching operations:

| HTTP route | MCP tool |
| --- | --- |
| `POST /api/v1/git/branches` | `list_git_branches` |
| `POST /api/v1/git/commits` | `list_git_commits` |
| `POST /api/v1/git/comparisons` | `compare_revisions` |
| `POST /api/v1/git/file-diff` | `get_file_diff` |
| `POST /api/v1/files` | `list_files` |
| `POST /api/v1/source` | `read_source` |
| `POST /api/v1/search-text` | `search_text` |

Follow returned cursors; an empty text-search page with `scanComplete: false`
does not establish absence. Lists/search default to20/max100, commit history
defaults to10, and source maxLines defaults to200/max500. Source payloads are
code-only: selected Java and mapper-root
XML from the imported plan, plus at most one configured, admitted Markdown project
guide. Configuration, unrelated text/XML, binary or oversized content, symlinks,
submodules, LFS pointers and unsupported paths do not become readable file rows.
Sealed generation coverage records excluded/unsupported counts, not their paths
or bodies. Both rename endpoints must qualify before a patch is persisted.

Comparisons always return `policyCoverage.excludedChanges` and general
reason/count pairs, including when items is empty. Entirely excluded changes
are distinguishable from equal/unchanged endpoints without excluded names or
content. Required counts are validated and bound to the immutable publication
digest; corruption is not a zero fallback. Granular source-restricted readers
are denied whole-comparison Git evidence, including these unscoped counts.

An AVAILABLE guide is readable source evidence but is excluded from code text
search and semantic facts. Its author provenance does not prove freshness.
Non-EMPTY_TREE snapshots bind an exact `sourceGenerationId`, revision, policy,
digest and guide membership; equivalent same-SHA generations are not substitutes.
Review comparisons use their endpoints' sealed source membership, not guessed source roots.

The configurable preparation defaults are 2 MiB text per file and 256 MiB total
admitted text per snapshot. Candidate guide reads obey the per-file limit, but
invalid guides do not consume the final snapshot budget. Admitted guide plus code
must still fit that total; otherwise preparation fails and never publishes READY.
Each READY manifest records its effective limits and coverage, so later
configuration changes do not reinterpret sealed evidence. Read
and patch payloads are capped at 64 KiB; search scans at most 4 MiB per call and
returns an incomplete cursor when that budget is exhausted. Query never uses Git, a
checkout, Indexer, JDT, or JDT LS to fill an incomplete result.

For a review-side literal search, copy the actual `after.context` from READY
discovery; the following placeholders illustrate the shape, not existing data:

```bash
curl -sS -H "X-Api-Token: $SEMANTIC_QUERY_API_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://query:8080/api/v1/search-text" \
  -d '{"context":{"kind":"REVIEW","repositoryId":"orders","reviewId":"<returned-review-id>","side":"AFTER","revision":"<returned-full-sha>"},"query":"PaymentService","limit":20}'
```

List pages use `page.hasMore` and the returned cursor, not caller offsets.
Source and patch continuation preserves exact UTF-8 bytes and UTF-16 ranges,
including non-BMP characters, EOF and CRLF boundaries. Send the same context,
filters and target with the cursor; do not derive a next line from a partial page.

## Reproducible operator check

The opt-in synthetic journey starts fresh temporary Mongo, independent Indexer
and Query JVMs, local Git and real JDT LS. It requires Java21, Maven3.9+, Docker
and JDTLS_HOME, and packages fresh executable jars. Its controlled host source
fixture explicitly uses LOCAL_TRUSTED; production LINUX_UID isolation belongs
to the separate image journey. It exercises MCP preparation/requestId recovery,
ordinary/root/merge COMMIT, equal/reversed/divergent RANGE, HTTP/native MCP
parity, policy exclusions, paging and cold Mongo-only Query. Its cross-JVM
READY digest recovery constructs the publication-before-terminal persisted
state after stopping Indexer; it is not a claim of observing an actual crash.
The default skipped test is not acceptance evidence.

```bash
JDTLS_HOME=/opt/jdtls MAVEN_CMD=/path/to/apache-maven-3.9.6/bin/mvn \
  bash scripts/test-git-review-context-journey.sh
```
