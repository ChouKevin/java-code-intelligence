# Git review context journey

Git evidence is a historical, Mongo-only Query capability. It is prepared by the
Indexer and stays readable by its returned immutable IDs after the remote branch
moves. It does not change a semantic generation pointer.

Run Indexer and Query with separate identities. The Indexer identity may submit
jobs under `/index/**`; the Query identity may only read Query HTTP and `/mcp`.
Keep `semantic.query.git-evidence.allowed-repositories` empty until
an administrator has explicitly approved whole-repository source evidence. A
repository with a forbidden repository, package, class, or method rule remains
denied for every Git-evidence operation.

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

The persisted Git evidence schema is `gitEvidenceVersion: 1`. Apply the additive
bootstrap before Indexer publication, and keep the returned IDs while operating
the matching Query release.

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
allow at most 500 lines. `coverage` reports inventory entries that cannot provide
searchable text, including binary, unsupported encoding, oversized, symlink,
submodule, LFS pointer, and unsupported path entries. A text file over 2 MiB is
retained as `TOO_LARGE` coverage rather than readable text; a snapshot exceeding
256 MiB total text fails preparation and never publishes READY. Read and patch
payloads are capped at 64 KiB; search scans at most 4 MiB per call and returns an
incomplete cursor when that budget is exhausted. Query never uses Git, a checkout,
Indexer, JDT, or JDT LS to fill an incomplete result.

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
