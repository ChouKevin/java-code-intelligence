# Java Code Intelligence

Java Code Intelligence builds a semantic index before query traffic arrives. It
contains two independently deployable applications and one shared model module:

- **Indexer** accepts private administrator commands, checks out exact Git
  commits, runs JDT LS, and seals/publishes immutable MongoDB generations.
- **Query** reads MongoDB only through HTTP and MCP. Current-generation calls
  use only the current sealed generation; separately prepared READY reviews can
  read their immutable BEFORE/AFTER membership. Query has no Git checkout, source
  fallback, JGit, JDT, JDT LS, or Indexer control.
- **Model** defines framework-neutral identities, facts, relations, repository
  values, and persisted index contracts.

There is no LLM, chat, prompt, embedding, vector model, review service, or
findings store in this repository. OMP is an external evidence client.

## Where changes belong

| Change | Location |
| --- | --- |
| Code-fact identities, relations, or persisted schema | `semantic-model/` |
| Git checkout, JDT LS, extraction, indexing, or publication | `semantic-indexer/` |
| Mongo reads, HTTP queries, MCP tools, or query security | `semantic-query/` |
| Deterministic UAT source | `semantic-indexer/fixtures/uat/` |

Keep Query independent from Git and JDT LS, and Model independent from Spring
and storage libraries. HTTP and MCP use the same Query facade and result
contract. Coding-agent guidance is in [AGENTS.md](AGENTS.md).

## Index and review flow

The private Indexer exposes four MCP tools at its own `/mcp`, using
`X-Api-Token: $SEMANTIC_INDEXER_ADMIN_TOKEN`. HTTP uses the same facade:

| MCP tool | HTTP route |
| --- | --- |
| `refresh_repository_metadata` | `POST /index/repositories/{repositoryId}/metadata` |
| `prepare_codebase` | `POST /index/repositories/{repositoryId}/codebase` |
| `prepare_review` | `POST /index/repositories/{repositoryId}/reviews` |
| `get_job` | `GET /index/repositories/{repositoryId}/jobs?jobId=…` or `?requestId=…` |

Before each preparation, the client creates and saves a canonical lowercase UUID
`requestId`. A `202` means that identity, the original request, and its job were
persisted together. Save `jobId` and poll the returned `Location`; lookup requires
exactly one selector and never substitutes the latest or active job.

`prepare_codebase` freshly resolves only the configured default branch and pins
its branch/SHA before acceptance. It accepts no branch, tag, or revision override.
The configured name resolves in `refs/heads/`; a tag never substitutes for a
missing branch.
Private maintenance routes remain available for explicit administrator operations.
Metadata refresh fetches once, prepares a branch catalog and history for the
requested branch (defaulting to the configured branch), and publishes one
`metadataPointer` only after both are READY. It runs no JDT analysis. The startup
registry includes configured but unindexed repositories without publishing URLs
or credentials.

HTTP never runs a build or reset inline. One `index-job-dispatcher` thread polls
the oldest `ACCEPTED` job, marks it `RUNNING`, and executes one job at a time.
Its only poll setting is:

```yaml
semantic:
  index-jobs:
    poll-delay: 1s
```

A normal build checks out only its stored commit, removes untracked and ignored
checkout content, runs the exporter, validates a generation, seals it, and
changes the repository pointer with expected-parent compare-and-set. Query
reads only that pointer. Every successful source response identifies the
published repository revision.

A review is not a PR diff and is not an arbitrary historical-generation API.
Submit `requestId` with an explicit `selection`: `COMMIT` compares the first
parent to the requested full SHA, while `RANGE` compares the supplied before/after
SHAs directly. A root commit uses the real empty tree and has no BEFORE semantic
generation. Admission neither captures current nor fetches Git; the dispatcher
resolves endpoints and prepares their semantic and Git evidence. Only complete
immutable READY membership is readable, and review preparation never moves
current. Equal, reverse, and divergent ranges do not imply a merge-base diff.

A job failure is one of `WORKER_INTERRUPTED`, `SOURCE_UNAVAILABLE`,
`SCHEMA_REBUILD_REQUIRED`, `PUBLICATION_CONFLICT`, `VALIDATION_FAILED`,
`ANALYSIS_UNAVAILABLE`, or `REVIEW_EVIDENCE_MISMATCH`. A lost response or timeout
means look up the **original** `requestId`, including after terminal completion,
a later repository job, or restart. `REQUEST_NOT_FOUND` means acceptance remains
unknown; continue lookup or stop waiting, not resubmit. `REQUEST_ID_REUSED`
returns the original job identity and accepts no new work.
Indexer storage failures use the same safe `INDEX_UNAVAILABLE` (HTTP 503) body
over HTTP and MCP. If acceptance is unknown, retain the original UUID and
continue lookup only; do not turn storage recovery into another submission.

A retry after an inspected failure is an explicit new administrator intent with
a new UUID; the dispatcher never retries automatically. At startup, configured
registry publication precedes dispatcher activation. Recovery recognizes a
fully published target or metadata pair as `COMPLETE`; otherwise a leftover
`RUNNING` job becomes `WORKER_INTERRUPTED`. It never publishes an orphan READY
metadata pair or resumes half-prepared work.

## Query contract

Authenticate every Query HTTP request and `/mcp` request with
`X-Api-Token: $SEMANTIC_QUERY_API_TOKEN`. Query needs only a Mongo read role;
Indexer has a different admin token, Mongo writer role, read-only Git
credential, checkouts, and JDT workspaces. See
[Semantic review deployment and operation](docs/operations/semantic-review.md)
for the single-VM topology, secret-file references, TLS, release, retention,
and OMP workflow.

MCP exposes thirty raw tool names:

- Current discovery/evidence: `list_repositories`, `get_repository`,
  `search_code`, `get_fact_source`, `list_entry_points`, `find_api_routes`,
  `find_event_listeners`, `list_type_members`, `find_method_implementations`,
  `find_references`, `find_callers`, `find_callees`
- Historical Git evidence: `list_git_branches`, `list_git_commits`,
  `compare_revisions`, `get_file_diff`, `list_files`, `read_file`,
  `search_text`
- READY-review discovery/side evidence: `get_review`, `review_search_code`,
  `review_get_fact_source`, `review_list_entry_points`,
  `review_find_api_routes`, `review_find_event_listeners`,
  `review_list_type_members`, `review_find_method_implementations`,
  `review_find_references`, `review_find_callers`, `review_find_callees`

The matching HTTP routes are documented in
[Semantic Query Operations](docs/operations/semantic-index-operations.md).
Current discovery returns the exact `repositoryId`/`revision` for the ten
current semantic tools. A stale request returns `REVISION_OUTDATED` with
`currentRevision`; rediscover fact IDs before a fact-bound retry.

Use `get_review` to discover READY BEFORE/AFTER revisions, side generation/snapshot IDs,
and comparison ID. Send `repositoryId`, `reviewId`, `side`, and exact side
`revision` to every `review_` semantic tool. Review-owned Git IDs pass the same
READY owner gate. Query never replaces side identity with current, exposes an
arbitrary generation ID, or starts Indexer to recover missing evidence.

Git evidence is Mongo-only and source-visible only for explicit
`semantic.query.git-evidence.allowed-repositories`; the allowlist is empty by
default. Existing repository/package/class/method/source/fact policies remain
fail-closed.

## Schema 4 and retention

The persisted release is schema version 4, with review version 2, Git evidence
version 3, and job version 3. Earlier data is not decoded into this contract:
there is no compatibility decoder, default, or handwritten migration.
Drain admissions, settle active jobs, back up coherent pointers/jobs/manifests/
payloads/Git evidence/review graphs, bootstrap schema 4 with maintenance
credentials, rebuild approved current generations, reprepare evidence as needed,
verify, deploy Query, then reopen admissions. Bootstrap includes the durable
repository-scoped requestId unique index; terminal request identities have no TTL.

A READY review retains its manifest, both generations, comparison, and
snapshots as one graph. There is no TTL, automatic garbage collection, or
snapshot-text deduplication: each comparison duplicates eligible text in its
own snapshots even when a semantic generation is reused. Manual cleanup checks
active jobs and all review references first. The complete procedure is in
[Tool projection data evolution](docs/operations/tool-data-evolution.md).

## Build and verification

Requirements: Java 21, Maven 3.9+, Docker for Mongo and image checks, and JDT
LS for Indexer smoke/end-to-end fixture checks. Run commands from the reactor
root:

```bash
mvn --batch-mode --no-transfer-progress test
mvn --batch-mode --no-transfer-progress -Pmongo-it verify
JDTLS_HOME=/opt/jdtls scripts/test-indexer-query-contract.sh
JDTLS_HOME=/opt/jdtls scripts/test-semantic-review-journey.sh
```

See [Testing and verification](docs/operations/testing.md) for entry-point
scope. The scripted local journey is not an OMP/model or remote VM/TLS
acceptance claim; the required actual-client record is described in
[MCP Agent Acceptance](docs/operations/mcp-agent-acceptance.md).
