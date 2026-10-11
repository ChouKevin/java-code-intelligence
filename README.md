# Java Code Intelligence — Source and Document MCP

A source-first service for traceable evidence from an approved Git repository at an **exact commit**, with optional Java syntax and static-relation indexes. Java21 and four modules: `semantic-indexer` privately fetches Git, publishes immutable source and builds separate sealed SQLite generations; `semantic-query` reads only published source/index through HTTP/MCP and real ripgrep; `semantic-model` contains JDK-only contracts; independent `document-query` serves pregenerated central-Git navigation through a separate read-only MCP. JavaParser and its optional Symbol Solver run only in Indexer. No service runs an LLM, MongoDB, target-repository builds or business inference. The preceding MCP reading/default-branch-current milestone was locally verified:248 ordinary tests, the packaged native journey and an actual Codex/MCP reading/citation scenario passed. See [historical evidence and release limits](docs/operations/testing.md#mcp-reading-and-current-local-evidence--2026-10-09). Relations acceptance is tracked separately in the [roadmap](docs/roadmap.md#phase-4--relations); prior results do not constitute Document MCP, Relations or release acceptance.

The corrected query skill also delivered every checked repository/exact-SHA
identity in all six final answers. The first-round omission and its fresh
verification remain recorded rather than being retroactively marked as passing.

## First use

Follow [Source MCP operations](docs/operations/source-mcp.md) for the single startup/onboarding guide: bootstrap a **new** service-owned local POSIX `/data` mount, inject separate secrets, register approved repositories on Indexer and configure Query's explicit allowlist. Query mounts **only** `source-published` and `index-published` read-only; Indexer owns the full `/data` parent. SQLite JNI extraction needs separate process-private writable/executable temporary storage, never a published mount. Registration does **not** prepare source or index. Keep Indexer private behind controlled TLS/ingress; expose Query only to authorized clients.

Repository IDs are durably bound to their approved Git origin in both storage
namespaces. Repointing an existing ID or adopting populated unbound pre-release
storage fails closed; see [origin identity and rebuild rules](docs/operations/source-mcp.md#repository-origin-and-storage-identity).

Keep real repository registrations, the Query allowlist, credentials and MCP
client settings local; the public repo contains only generic configuration and
synthetic fixtures. See [local-only configuration and evidence](docs/operations/source-mcp.md#local-only-configuration-and-evidence).

For agent-operated registration, dependency preparation and JavaParser enablement,
load [onboard-repository](skills/onboard-repository/SKILL.md). It connects the
operations guide into an authorized exact-revision workflow, distinguishes syntax
from opt-in Relations, and requires Query-only acceptance. It does not authorize
automatic downloads, target builds, existing-service restarts or description approval.

For agent-assisted repository descriptions and pregenerated navigation, read
[describe-repository](skills/describe-repository/SKILL.md). The author may read an
approved local project's exact tracked Git blobs directly; MCP publication,
JavaParser indexing and target builds are not prerequisites. Real drafts stage in
`.local/repository-descriptions/<repositoryId>.md` and
`<repositoryId>-module-guide.md`, retaining `DRAFT` / `NOT_VERIFIED`.

The [central document handoff](docs/operations/repository-context-prompt.md#central-document-handoff)
uses a separate Git document repository for human-reviewed versions. Every update
requires approval of its exact content and analyzed source revisions, plus separate
publication authority; no business-repo commit or `prepare_source` is required.
Latest approved navigation remains available while an explicit per-repo refresh is
pending or fails. [Document MCP](docs/operations/document-mcp.md) now reads this
independent Git catalog through `search_documents` and full `read_document`.
New searches use active; retained exact document reads/continuations stay pinned.
Elasticsearch remains future work. Document commits and business-source SHAs are
independent; TEST_ONLY fixture permission never becomes formal approval.

The existing [module-aware template](docs/operations/repository-context-prompt.md#module-aware-guide-mvp)
contains repo/module navigation, evidence-backed business lookup starts and
cross-repo clues. It is not a complete business-answer database or deployment
proof. Query agents use documents to prioritize source investigation, then expand
when evidence is missing; full source citations still use exact repo/SHA/path/lines.
The internal-reader scope adds no differential ACL or relationship graph.

Existing optional [business-discovery hints](docs/operations/source-mcp.md#optional-human-reviewed-business-discovery-hint)
still require separately authorized Indexer configuration publication, and
repo-local `project-guide-path` remains readable through source tools. Neither is
the central document publishing mechanism; drafting alone changes neither service.

For read-only questions about module responsibilities, build dependencies, business
rules, calculations, eligibility, features or service flows, read the separate
[query-business-code](skills/query-business-code/SKILL.md) skill. It applies
beyond VIP questions and uses source evidence, not the hint text. Use
`describe-repository` to author a description; use `query-business-code` to
answer how code behaves.

Explicit portable file-reading invocations work without global skill installation:

```text
請先讀取 skills/describe-repository/SKILL.md。
我要新增一個 repo；請依我提供的 repositoryId、核准 clone 或 MCP context、
固定 SHA 與閱讀範圍，撰寫 repo 描述與關鍵詞，交由我審閱。
```

```text
請先讀取 skills/query-business-code/SKILL.md。
我要知道「<業務規則／計算／條件／服務流程問題>」如何實作；
請使用已授權 Query MCP 查證，並為每個 repo/SHA/path/line 提供引用。
```

`AGENTS.md` links both workflows for clients that load project instructions.
Other clients can read either file explicitly; automatic skill installation or
guaranteed natural-language triggering is not promised. MCP tool descriptions
are optional hints, not a client-side router. Description authoring is optional
and is not a prerequisite for source or index preparation.

Save a canonical lowercase UUID `requestId` durably **before** calling Indexer `prepare_source` (`POST /index/repositories/{repositoryId}/source` or private `/mcp`); optional `revision` must be a full lowercase 40-character SHA-1 commit. Omitting `revision` explicitly fetches and pins the configured default-branch HEAD; successful publication updates source current. Supplying an exact SHA prepares/reuses readable membership only and never changes current, even when it equals current. Current is the SHA from the most recent successful explicit default-branch refresh, not a live remote lookup. Get the original job by **either** `jobId` or `requestId` at `GET /index/repositories/{repositoryId}/jobs`. If the acceptance response is lost, look up the **original** request ID; do not resubmit or auto-retry an unknown outcome. Once COMPLETE, discover with `get_context`, copy the returned `{repositoryId, revision}` and retain it for every navigation call. Revision A remains readable after B is published. Before relying on the new current baseline in an existing deployment, an admin must complete one successful `prepare_source` with no `revision`; if it fails, do not claim the baseline changed. See [agent acceptance](docs/operations/mcp-agent-acceptance.md) and [migration guidance](docs/operations/source-mcp.md#current-source-and-upgrade-migration).

After source completion, explicitly submit `prepare_semantic_index` for that exact
SHA with a separately saved canonical UUID. New semantic-index preparation accepts
only the already-published current SHA; absent or changed current fails with
`INDEX_NOT_CURRENT` (HTTP 409). It never changes source current or substitutes a
SHA. This is only a new-index admission rule: retained indexes for historical
revisions remain readable, and old committed index receipts remain recoverable.
`get_job` identifies `operation: prepare_source|prepare_semantic_index`; request
recovery uses exactly one selector and the original UUID for either operation.
Each revision selects its own index generation; pinned generations/cursors never
switch to a rebuild or latest. Readiness and Java parse coverage are independent
of source readiness. Syntax candidates are navigation evidence, not resolved
runtime routes/calls; always confirm with `read_source` at the same SHA.

With Relations disabled (the default), new generations use fold-capable schema/policy
`2/2`. Explicit Indexer Relations configuration selects `3/3`, with static Java
declaration resolution against Java 21, admitted source roots and approved frozen
local JARs. Profile `1/1` remains syntax-readable; `2/2` adds folds and `3/3` adds
relations. No existing generation is rewritten or upgraded in place. See
[Relations setup and safety](docs/operations/source-mcp.md#static-relations).

Source policy **2** adds scheduled revision retention: non-current snapshots remain
for at least 30 elapsed days after replacement; current is never reclaimed.
Indexer checks when idle at startup, then every 24h after a scan ends. Busy reads
or preparation can delay cleanup. `SEMANTIC_SOURCE_RETENTION_ENABLED=false`
stops new and pending physical deletion; the interval is configurable.
Deploy both applications together on fresh private/published namespaces; policy-1
trees are not migrated. A reclaimed exact context fails explicitly rather than
reading latest. See [retention and recovery](docs/operations/source-mcp.md#revision-retention-and-recovery).

Indexer optionally checks managed logical bytes and minimum usable disk space
before Git resolution, after revision pinning and after export, and during index
building. Accounting includes private, source and index roots. Capacity refusal
is a durable FAILED job; existing READY source/index remain readable. Both thresholds
default to disabled. These are application admission checks, **not filesystem
hard quotas**, and do not force early retention cleanup. Configuration, failure
codes and recovery are in [capacity admission](docs/operations/source-mcp.md#application-capacity-admission).

## Interfaces

Indexer/Source Query application calls require `X-Api-Token`: `SEMANTIC_INDEXER_ADMIN_TOKEN` for private preparation, `SEMANTIC_QUERY_API_TOKEN` for source reading. Their HTTP and MCP surfaces share input validation, results and safe errors.

Query MCP uses an initialized Streamable HTTP session per client; retain the
server-issued session ID for calls and cancellation, then close the session.
See the [MCP session lifecycle](docs/operations/source-mcp.md#mcp-session-lifecycle).

Document MCP is a separate **stateless** `/mcp` server with only
`search_documents` and `read_document`, authenticated by its own
`DOCUMENT_QUERY_API_TOKEN` through `X-Api-Token`. It has no business REST API or
publisher API. Register both read MCPs in the querying client and load the query
skill; document snippets guide discovery, full documents preserve qualifications,
and exact Source Query reads support business conclusions. Document citations use
`documentRepo@fullDocumentCommit:path#L<sectionStartLine>`, never a source context.

| Indexer MCP | Private HTTP |
| --- | --- |
| `prepare_source` | `POST /index/repositories/{repositoryId}/source` |
| `prepare_semantic_index` | `POST /index/repositories/{repositoryId}/semantic-index` |
| `get_job` | `GET /index/repositories/{repositoryId}/jobs?requestId=…` or `?jobId=…` |

| Query MCP | Read HTTP |
| --- | --- |
| `list_repositories` | `GET /api/v1/repositories` |
| `get_context` | `POST /api/v1/context` |
| `list_files` | `POST /api/v1/files` |
| `search_text` | `POST /api/v1/search-text` |
| `read_source` | `POST /api/v1/source` |
| `search_code` | `POST /api/v1/search-code` (required literal `match: exact|prefix`) |
| `get_outline` | `POST /api/v1/outline` |
| `list_entry_points` | `POST /api/v1/entry-points` |
| `find_relations` | `POST /api/v1/relations` (required exact `indexGeneration`) |

`get_context` takes top-level `repositoryId` and optional exact `revision`; navigation uses nested `context` containing **only** `repositoryId` and `revision`. The three syntax tools optionally accept a top-level canonical UUID `indexGeneration`; `find_relations` requires it on every page. Keep it separate from source context. No old CURRENT/REVIEW/fact/branch selector or legacy alias is supported. A registered but unprepared repository has no readable context. An optional externally reviewed [project guide](docs/operations/repository-context-prompt.md) is a `NOT_VERIFIED` navigation hint, never a fact or preparation gate.

`list_files` defaults to one directory level. `depth: 2` returns a bounded flat
preorder preview with at most 12 immediate children per direct-child directory;
`childPreview: ELIDED` requires an explicit listing of that directory. The global
page limit remains 20 by default and 100 maximum.

`read_source` defaults to `view: auto`: without a selector, eligible Java with
published fold metadata may use a summary; otherwise auto reads raw source and
reports why. `view: raw` never needs an index. `view: summary` requests only the
whole-file structural view and fails explicitly when unavailable; it never silently
falls back (`SOURCE_SUMMARY_UNAVAILABLE`, HTTP 409, with a bounded reason).
Explicit `startLine`, up to 20 inclusive `ranges`, or
`tailLines` (up to 500) selects raw text, as does any explicit selector under
`auto`. `maxLines` is a per-page limit (default 200, max 500), not a total read
limit; source content is capped at 65,536 bytes and the serialized HTTP/MCP result
at 512 KiB. Responses contain source/elision `segments` with exact raw UTF-8
byte spans, original LF line coordinates, blob/content digests and, for summaries,
index generation/profile provenance. Elided method/constructor bodies were not
read: use same-context raw ranges to verify them before citing behavioral claims.
Raw returns the exact original bytes. `SOURCE` text and `ELISION` spans stay
separate; an elision is unread source, not page continuation. There is no
top-level continuous `content` alias.
Every cursor pins source, selection, effective view and any summary generation.
Read cursors are authenticated and valid only within their issuing Query process.
After restart, keep the exact context and retained generation pin but start a new
read; old or modified tokens return `INVALID_ARGUMENT`, never a different view.

Successful MCP `read_source` text content is a numbered display: exact
repository/SHA/path locators, original LF line labels, explicit elisions and
partial-line flags. It is not JSON and its display labels/escapes are not source
bytes. HTTP JSON and MCP `structuredContent` retain the same lossless source
segments, provenance and cursor contract. Machine consumers must use those
structured fields, not parse display text as JSON or source. The 512 KiB MCP
budget includes both forms; numbered output aids citation but does not validate
a model's final claims or citations.


## Development and evidence

| Responsibility | Module |
| --- | --- |
| Git registry, durable source/index jobs, exact export, atomic source/index publication | `semantic-indexer/` |
| Published-only admission, bounded filesystem/ripgrep/SQLite reads, HTTP/MCP | `semantic-query/` |
| Central Git navigation catalog, pinned document search/read, independent stateless MCP | `document-query/` |
| Exact source identity, generation/profile/coverage and framework-neutral contracts | `semantic-model/` |
| Video source corpus; order/payment guide cases generated by the journey | `semantic-indexer/fixtures/uat/` |

Keep Query independent from Indexer implementation, JGit, private Git credentials and mutation; keep Model independent from frameworks. See [AGENTS.md](AGENTS.md) for coding boundaries and [testing](docs/operations/testing.md) for current commands and observed limitations. The source journey uses real Git/ripgrep and separate processes; passing it does not prove a particular external model, private repository, remote TLS deployment or business conclusion. See the [operations guide](docs/operations/source-mcp.md#release-and-recovery) for rebuild and rollback.

Use `bash scripts/verification-scope.sh --base <commit> --head <commit>` to select
final coverage (`--full` for shared/uncertain scope); selection is not permission
to execute it. Authorized local verification requires resource prechecks, serial
execution, bounded JVMs and disposable test roots. Current results and remaining
acceptance limits are in [testing](docs/operations/testing.md). Docker/image gates,
commit/push/PR and mutation of existing services/data require separate authorization.
