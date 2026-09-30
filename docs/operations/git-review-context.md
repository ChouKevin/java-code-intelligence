# Git review context journey

Git evidence is a historical, Mongo-only Query capability. It is prepared by the
Indexer and stays readable by its returned immutable IDs after the remote branch
moves. It does not change a semantic generation pointer.

Run Indexer and Query with separate identities. The Indexer identity may submit
jobs under `/index/**`; the Query identity may only read Query HTTP and `/mcp`.
Keep `semantic.query.git-evidence.allowed-repositories` empty until an
administrator has explicitly approved whole-repository source evidence. A
repository with a forbidden repository, package, class, or method rule remains
denied for every Git-evidence operation. Query has only Mongo read access; it
never clones, fetches, opens a checkout, or starts Indexer to serve Git evidence.

## Prepare immutable evidence

First stop the old Indexer and settle every active job through its normal terminal
recovery. Then apply the additive schema bootstrap with the schema-maintenance
identity, deploy the new Indexer, prepare and verify evidence, and only then deploy
the new Query release. See [tool-data-evolution.md](tool-data-evolution.md) for the
maintenance/bootstrap and rebuild order. A changed persisted meaning requires an
explicit cutover and rebuild; this version does not use compatibility decoders.

With an admin token and a configured repository, submit each job and poll its
repository-scoped status until `phase` is `COMPLETE`:

```bash
curl -sS -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" -X POST \
  "http://indexer:8080/index/repositories/orders/git/refs"
# Save jobId, then GET /index/repositories/orders/jobs/{jobId}; save gitEvidence.evidenceId as catalogId.

curl -sS -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://indexer:8080/index/repositories/orders/git/history" \
  -d '{"catalogId":"<catalogId>","branch":"main","revision":"<lowercase-40-sha>"}'
# Save gitEvidence.evidenceId as historyId.

curl -sS -H "X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://indexer:8080/index/repositories/orders/git/comparisons" \
  -d '{"previous":"<lowercase-40-sha>","current":"<lowercase-40-sha>"}'
# Save gitEvidence.comparisonId, previousSnapshotId, and currentSnapshotId.
```

Each command returns `202` and a `jobId`; completion returns the typed evidence
IDs. Retry a failed or interrupted job by submitting a new command. Query reads
only READY evidence, so an owned pending ID returns retryable
`GIT_EVIDENCE_NOT_READY`; unknown or denied repositories/evidence return 404.
READY manifests are retained manually until an operator performs data maintenance:
there is no automatic TTL or garbage collection.

The schema-4 release uses `gitEvidenceVersion: 3`. Apply the schema bootstrap
before Indexer publication, then rebuild/reprepare evidence as required by
[tool-data-evolution.md](tool-data-evolution.md). Earlier schema/evidence versions
are not decoded into this contract. Bootstrap is maintenance-only; runtime Indexer
uses its writer role and Query uses a separate reader role.

## Review-owned comparison evidence

Do not use the standalone comparison endpoint to claim a semantic review was
prepared. A private review submission selects either a `COMMIT` SHA or explicit
`RANGE` before/after SHAs. The dispatcher job prepares the direct comparison
and its available semantic endpoints without reading or publishing the mutable
current pointer. A root `COMMIT` compares the real empty tree to that commit:
it has no before semantic generation, but does have a previous snapshot of the
empty tree. The job's `review` status supplies `reviewId`, `comparisonId`,
`previousSnapshotId`, and `currentSnapshotId` only when it is READY.

`get_review` returns those immutable IDs to an authorized Query client. The
ordinary `compare_revisions`, `get_file_diff`, `list_files`, `read_file`, and
`search_text` tools use exact returned IDs and SHAs. For an empty-tree root,
omit the `previous` argument to `compare_revisions` and `get_file_diff`; their
responses report `previous: null`, and the root file diff contains `ADD` patches.
Review-owned rows also require the owning review manifest to be READY and owned
by the same job. IDs from a PREPARING or FAILED review cannot bypass the gate.
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

Use the returned identities unchanged. Branch discovery may omit `catalogId` only
for its first page; copy its returned `catalogId` on every continuation. History,
comparison, diff, file and search requests always carry the returned evidence ID
and exact SHA. The seven matching HTTP routes and MCP tools are:

| HTTP route | MCP tool |
| --- | --- |
| `POST /api/v1/git/branches` | `list_git_branches` |
| `POST /api/v1/git/commits` | `list_git_commits` |
| `POST /api/v1/git/comparisons` | `compare_revisions` |
| `POST /api/v1/git/file-diff` | `get_file_diff` |
| `POST /api/v1/git/files` | `list_files` |
| `POST /api/v1/git/file` | `read_file` |
| `POST /api/v1/git/search` | `search_text` |

Continue whenever a response supplies `nextCursor`; a `search_text` result is
complete only when `scanComplete` is true and it has no continuation. List and
search pages default to 20 and allow at most 100 items; reads default to 200 and
allow at most 500 lines. Source payloads are code-only: selected Java and mapper-root
XML from the imported plan, plus at most one configured, admitted Markdown project
guide. Configuration, unrelated text/XML, binary or oversized content, symlinks,
submodules, LFS pointers and unsupported paths do not become readable file rows.
Sealed generation coverage records excluded/unsupported counts, not their paths
or bodies. Both rename endpoints must qualify before a patch is persisted.

An AVAILABLE guide is readable source evidence but is excluded from code text
search and semantic facts. Its author provenance does not prove freshness.
Non-EMPTY_TREE snapshots bind an exact `sourceGenerationId`, revision, policy,
digest and guide membership; equivalent same-SHA generations are not substitutes.
Standalone comparisons require already sealed source membership for both revisions.

The configurable preparation defaults are 2 MiB text per file and 256 MiB total
text per snapshot. A snapshot exceeding its total limit fails preparation and
never publishes READY. Each READY manifest records its effective limits and
coverage, so later configuration changes do not reinterpret sealed evidence. Read
and patch payloads are capped at 64 KiB; search scans at most 4 MiB per call and
returns an incomplete cursor when that budget is exhausted. Query never uses Git, a
checkout, Indexer, JDT, or JDT LS to fill an incomplete result.

For example, carry the `repositoryId`, `currentSnapshotId`, and `current` SHA
returned by the comparison job unchanged into a current-source read or search:

```bash
curl -sS -H "X-Api-Token: $SEMANTIC_QUERY_API_TOKEN" -H 'Content-Type: application/json' -X POST \
  "http://query:8080/api/v1/git/search" \
  -d '{"repositoryId":"orders","snapshotId":"<currentSnapshotId>","revision":"<current-sha>","query":"PaymentService","limit":20}'
```

Branches, history, comparisons, and file listings use the returned `page.hasMore`
with the caller's `offset` and `limit`. Diff, file, and search continuations use
an opaque `nextCursor`: send it back unchanged with the same evidence identity and
request fields; never derive an offset from it.

## Reproducible operator check

The repository-owned synthetic external-client journey has no JDT requirement and
is deliberately opt-in because it starts temporary MongoDB and local application
processes. It requires Java 21, Maven 3.9+, and a reachable Docker daemon for its
temporary MongoDB container. It first packages fresh executable jars, then performs
admin HTTP, dispatcher/READY publication, Query HTTP, and MCP SDK calls:

```bash
MAVEN_CMD=/path/to/apache-maven-3.9.6/bin/mvn bash scripts/test-git-review-context-journey.sh
```
