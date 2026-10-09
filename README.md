# Java Code Intelligence — Source MCP

A source-first service for traceable evidence from an approved Git repository at an **exact commit**, with an optional lightweight Java syntax index. Java 21 and three modules remain: `semantic-indexer` privately fetches Git, publishes immutable source and builds separate sealed SQLite generations; `semantic-query` reads only published source/index through HTTP/MCP and real ripgrep; `semantic-model` contains JDK-only contracts. JavaParser runs only in Indexer. Neither service runs an LLM, MongoDB, target-repository build, symbol solver or business inference. The MCP reading/default-branch-current milestone is locally verified: 248 ordinary tests, the packaged native journey and an actual Codex/MCP reading/citation scenario passed. See [current evidence and release limits](docs/operations/testing.md#mcp-reading-and-current-local-evidence--2026-10-09). Earlier Phase 3 and business-discovery results, including six source-only model scenarios, remain historical evidence; none constitute release acceptance.

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

For agent-assisted repository descriptions, read
[describe-repository](skills/describe-repository/SKILL.md). It drafts a
human-reviewed business summary, Chinese/English keywords, example questions and
exact-revision source citations. Real drafts default to
`.local/repository-descriptions/<repositoryId>.md`; they remain `DRAFT` /
`NOT_VERIFIED` and are not automatically imported, registered or published.
Publishing an approved hint requires explicit human approval of its exact content
and analyzed revision, separately authorized Indexer external-configuration
changes and an explicit Indexer start/restart; see
[business-discovery operations](docs/operations/source-mcp.md#optional-human-reviewed-business-discovery-hint).

For questions about implemented business rules, calculations, eligibility or
upgrade conditions, features, or service flows, read the separate
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

New index generations use the fold-capable JavaParser schema/policy profile
`2/2`; the known legacy `1/1` profile remains readable for syntax queries but
has no fold capability. No existing generation is rewritten or upgraded in place.

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

Every HTTP and `/mcp` call requires `X-Api-Token`: `SEMANTIC_INDEXER_ADMIN_TOKEN` for private preparation, `SEMANTIC_QUERY_API_TOKEN` for reading. HTTP and MCP share input validation, results and safe errors.

Query MCP uses an initialized Streamable HTTP session per client; retain the
server-issued session ID for calls and cancellation, then close the session.
See the [MCP session lifecycle](docs/operations/source-mcp.md#mcp-session-lifecycle).

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

`get_context` takes top-level `repositoryId` and optional exact `revision`; navigation uses nested `context` containing **only** `repositoryId` and `revision`. The three index tools optionally accept a top-level canonical UUID `indexGeneration`, separate from source context. No old CURRENT/REVIEW/fact/branch selector or legacy alias is supported. A registered but unprepared repository has no readable context. An optional externally reviewed [project guide](docs/operations/repository-context-prompt.md) is a `NOT_VERIFIED` navigation hint, never a fact or preparation gate.

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


## Development and evidence

| Responsibility | Module |
| --- | --- |
| Git registry, durable source/index jobs, exact export, atomic source/index publication | `semantic-indexer/` |
| Published-only admission, bounded filesystem/ripgrep/SQLite reads, HTTP/MCP | `semantic-query/` |
| Exact source identity, generation/profile/coverage and framework-neutral contracts | `semantic-model/` |
| Video source corpus; order/payment guide cases generated by the journey | `semantic-indexer/fixtures/uat/` |

Keep Query independent from Indexer implementation, JGit, private Git credentials and mutation; keep Model independent from frameworks. See [AGENTS.md](AGENTS.md) for coding boundaries and [testing](docs/operations/testing.md) for current commands and observed limitations. The source journey uses real Git/ripgrep and separate processes; passing it does not prove a particular external model, private repository, remote TLS deployment or business conclusion. See the [operations guide](docs/operations/source-mcp.md#release-and-recovery) for rebuild and rollback.

Use `bash scripts/verification-scope.sh --base <commit> --head <commit>` to select
final coverage (`--full` for shared/uncertain scope); selection is not permission
to execute it. Authorized local verification requires resource prechecks, serial
execution, bounded JVMs and disposable test roots. Current results and remaining
acceptance limits are in [testing](docs/operations/testing.md). Docker/image gates,
commit/push/PR and mutation of existing services/data require separate authorization.
