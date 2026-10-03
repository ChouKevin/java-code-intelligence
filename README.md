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

Neither application runs an LLM, chat, embedding/vector model, or findings store.
OMP, Codex and Claude Code are external evidence clients. An optional
[external guide-authoring prompt](docs/operations/repository-context-prompt.md)
is an operator handoff document, not a service-side model runtime.

## First use

Follow the [startup and repository onboarding checklist](docs/operations/semantic-review.md#startup-and-repository-onboarding):
provision/bootstrap the services, configure an approved Git URL and fixed branch,
approve Query source access, connect the MCP endpoints, then save a request ID and
prepare the first codebase. Startup registration alone does not create an index.

An [externally authored project guide](docs/operations/repository-context-prompt.md)
is optional, not a prerequisite summary. Review and commit it to the configured
branch before preparing a generation that should include it. Query-only readers
do not need Indexer administration credentials.

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

Query exposes thirteen tools, with the same inputs/results through HTTP:

- Discovery: `list_repositories`, `get_context`
- Code/source: `search_code`, `list_files`, `search_text`, `read_source`,
  `list_entry_points`, `get_outline`, `find_relations`
- Git: `list_git_branches`, `list_git_commits`, `compare_revisions`,
  `get_file_diff`

The matching routes and request examples are in
[Semantic Query Operations](docs/operations/semantic-index-operations.md).
`get_context` accepts `CURRENT`, `REVIEW`, `COMMIT`, or `RANGE` discovery
selectors; discovery never prepares evidence. Copy returned READY `context`
or review `before.context`/`after.context` into the same navigation tools.
CURRENT requires the exact published revision. `REVISION_OUTDATED` requires
rediscovery and new fact IDs, not silent revision replacement.

Review contexts require exact `repositoryId`, `reviewId`, `side`, and side
`revision`. Copy `comparisonContext` into comparison/patch calls. A root
commit has an `EMPTY_TREE` before endpoint and no BEFORE semantic context.
Generation, snapshot and comparison IDs are not public context overrides.
There are no separate `review_` navigation tools or old-route aliases.

Only selected Java, mapper-root XML, and at most one configured valid Markdown
project guide are readable. Generic configuration documents are excluded before
publication. Guides are marked `PROJECT_GUIDE`, retain separate author
`analyzedRevision` and actual `importedRevision`, and always report
`freshness: NOT_VERIFIED`; they do not become semantic facts or text-search
evidence. Missing/invalid guides do not block code indexing. See the
[guide prompt and review procedure](docs/operations/repository-context-prompt.md).

No configured repository is indexed automatically. A fresh database can expose
`UNINDEXED`; an active BUILD is reported separately from a published current.
While preparing a newer revision, reads keep the old published identity until
publication. Failed preparation preserves the old pointer; inspect the original
job rather than infer success from metadata refresh or admission.

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
JDTLS_HOME=/opt/jdtls scripts/test-git-review-context-journey.sh
```

See [Testing and verification](docs/operations/testing.md) for entry-point
scope. The scripted local journey is not an OMP/model or remote VM/TLS
acceptance claim; the required actual-client record is described in
[MCP Agent Acceptance](docs/operations/mcp-agent-acceptance.md).
