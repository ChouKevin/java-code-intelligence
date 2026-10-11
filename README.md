# Java Code Intelligence — Source and Document MCP

Read approved Git repositories at an **exact commit**, navigate optional Java syntax/static relations, and use pregenerated documents to find relevant source. Source is authoritative; documents and indexes do not establish runtime behavior or business conclusions.

## Modules

Java 21, Maven 3.9.x, four modules:

| Module | Responsibility |
| --- | --- |
| `semantic-indexer/` | Private registration, Git credentials/objects, durable jobs, immutable source publication, optional JavaParser/SQLite indexing, capacity and retention |
| `semantic-query/` | Read-only published source/index access through HTTP/MCP, real ripgrep and SQLite; no private Git storage or Indexer dependency |
| `document-query/` | Independent read-only document MCP plus an explicitly invoked offline local-Git document command |
| `semantic-model/` | JDK-only identity and publication contracts |

JavaParser and its optional Symbol Solver run only in Indexer. No service runs an LLM, builds target repositories or automatically downloads their dependencies. Source reading works without a semantic index.

## First use

1. **Deploy and register.** Follow [Source MCP operations](docs/operations/source-mcp.md#bootstrap-and-trust-boundaries). Give Indexer its private storage/admin token; give Source Query only read-only published source/index mounts, its own read token and an explicit repository allowlist. Registration alone does not prepare source.
2. **Prepare source explicitly.** Persist a canonical UUID `requestId`, then call private `prepare_source`. Without `revision`, successful publication refreshes the configured default branch and advances current. With a full exact SHA, it adds/reuses readable membership without changing current. Resolve uncertain acceptance through the original request ID, not a new request.
3. **Read through Source Query.** Discover repositories, obtain `get_context`, then list/search/read at that exact context. Query does not fetch or prepare missing revisions.
4. **Add optional capabilities separately.** Prepare syntax/Relations only when requested; use the independent Document MCP for pregenerated navigation. Neither is a prerequisite for raw source reads.

Keep actual registrations, allowlists, credentials and MCP client settings outside tracked files. See [local configuration](docs/operations/source-mcp.md#local-only-configuration-and-evidence). Repository IDs bind permanently to their approved origins; changing an origin requires a new ID/fresh namespace, not repointing existing storage.

### Agent entry points

| Request | Skill |
| --- | --- |
| Register a repository, refresh source, prepare an exact SHA, enable indexing, update/activate local documents | [manage-repository](skills/manage-repository/SKILL.md) |
| Draft/update repository descriptions or module/relationship guides | [describe-repository](skills/describe-repository/SKILL.md) |
| Explain business rules, calculations, eligibility or service flows from source | [query-business-code](skills/query-business-code/SKILL.md) |

Portable invocations do not require global skill installation:

```text
請先讀取 skills/manage-repository/SKILL.md，依我提供的核准 repo 與設定完成 source-only 引入。
```

```text
請先讀取 skills/describe-repository/SKILL.md，依核准 clone、固定 SHA 與閱讀範圍產生 repo 描述草稿。
```

```text
請先讀取 skills/query-business-code/SKILL.md，使用已授權的文件／Source Query MCP 查證 VIP 等級如何計算，附完整 repo/SHA/path/line 引用。
```

Clients must load [AGENTS.md](AGENTS.md) or explicitly read the relevant skill. Natural-language triggering and automatic skill installation are not guaranteed by MCP registration or tool descriptions. Skills do not grant permission to mutate services, fetch/build repositories or publish documents.

## Local documents and source discovery

An external author agent reads approved exact source and produces a `DRAFT` / `NOT_VERIFIED` description or guide. Real drafts default to `.local/repository-descriptions/`; use the [canonical handoff and template](docs/operations/repository-context-prompt.md).

The central document repository is **local Git, with no remote**. The [offline document command](docs/operations/document-mcp.md#local-git-document-commands) updates explicitly named Markdown, generates catalog/digests, creates a local commit and atomically selects the readable version. Users do not hand-edit catalog JSON. It preserves unrelated working edits, refuses pre-existing staged changes, and supports status, explicit activation/rollback and withdrawal. It does not modify or prepare business repositories.

Formal `APPROVED` collections require review of the exact content/source identities and separate activation authority. Explicitly authorized test fixtures may remain `TEST_ONLY` / `DRAFT`; this is not production approval. `serviceVerified:false` means local success still needs verification through the running MCP.

Document search uses active; retained exact reads and continuations stay pinned. Document commits and source SHAs are independent. Read the full document behind a hit, then verify conclusions against the requested source version. Missing or stale navigation does not exclude repositories or permit switching source revisions.

The optional short `navigation-hint` and repo-local `project-guide-path` remain separate discovery mechanisms. They are not prerequisites for central documents. Elasticsearch, vector search, scheduled document regeneration and automatic cross-repository graphs are not implemented.

## Interfaces

All application calls use `X-Api-Token`, with separate credentials:

| Surface | Credential | Role |
| --- | --- | --- |
| Private Indexer HTTP/MCP | `SEMANTIC_INDEXER_ADMIN_TOKEN` | Preparation and job inspection |
| Source Query HTTP/MCP | `SEMANTIC_QUERY_API_TOKEN` | Published source/index reads |
| Document MCP | `DOCUMENT_QUERY_API_TOKEN` | Published navigation reads |

Source Query uses an initialized Streamable HTTP session per client; retain and close its session as described in [MCP lifecycle](docs/operations/source-mcp.md#mcp-session-lifecycle). Document MCP is a separate stateless `/mcp` server. Querying agents receive the read surfaces, never Indexer admin access.

| Indexer MCP | Private HTTP |
| --- | --- |
| `prepare_source` | `POST /index/repositories/{repositoryId}/source` |
| `prepare_semantic_index` | `POST /index/repositories/{repositoryId}/semantic-index` |
| `get_job` | `GET /index/repositories/{repositoryId}/jobs?requestId=…` or `?jobId=…` |

| Source Query MCP | Read HTTP |
| --- | --- |
| `list_repositories` | `GET /api/v1/repositories` |
| `get_context` | `POST /api/v1/context` |
| `list_files` | `POST /api/v1/files` |
| `search_text` | `POST /api/v1/search-text` |
| `read_source` | `POST /api/v1/source` |
| `search_code` | `POST /api/v1/search-code` |
| `get_outline` | `POST /api/v1/outline` |
| `list_entry_points` | `POST /api/v1/entry-points` |
| `find_relations` | `POST /api/v1/relations` |

Document MCP exposes only `search_documents` and `read_document`; it has no publisher API or business REST API. See [Document MCP operations](docs/operations/document-mcp.md) for its command, schemas and bounds.

## Version and evidence rules

- Source identity is `repositoryId + revision`, with a full lowercase 40-character SHA. `get_context` discovers current or an explicitly requested retained revision; navigation uses that fixed nested `context`. Unknown/expired pins fail explicitly, never fall back to latest.
- Current means the most recent successful explicit default-branch refresh, not a live remote lookup. Different clients can read different published commits concurrently.
- New `prepare_semantic_index` work accepts only the already-published exact current SHA. Historical source/index reads and committed receipt recovery remain supported independently of that admission rule. Each index generation has its own canonical UUID; `find_relations` requires it on every page.
- Syntax/folds default to profile `2/2`; opt-in Relations uses `3/3` with approved frozen source roots, ordered local JARs and Java 21 identity. Readers support known profiles `1/1`, `2/2`, `3/3`. No generated members, target execution or runtime-call-graph claims. See [Java indexing and Relations](docs/operations/source-mcp.md#static-relations).
- `read_source` supports raw ranges, tail reads, pagination and eligible Java structure summaries. Omitted bodies are unread: fetch their exact raw ranges before making behavioral claims. MCP numbered text aids citation; HTTP JSON/MCP `structuredContent` retain authoritative byte spans and metadata. Cursors pin their original selection/view and are process-authenticated, not durable bookmarks.
- Source citations use `sourceRepo@fullSourceSHA:path:Lstart-Lend`; document citations use `documentRepo@fullDocumentSHA:path#LsectionStartLine`. Neither short identifiers nor document analysis SHAs substitute for source context.
- Source policy 2 protects current and retains replaced revisions for at least 30 elapsed days; busy reads can defer collection. Optional capacity checks are admission checks, not hard quotas or forced GC. See [retention](docs/operations/source-mcp.md#revision-retention-and-recovery) and [capacity](docs/operations/source-mcp.md#application-capacity-admission).

## Development and verification

Use [AGENTS.md](AGENTS.md) for repository contracts, [testing](docs/operations/testing.md) for prerequisites/commands and [agent acceptance](docs/operations/mcp-agent-acceptance.md) for independent client evidence. Agent-based MCP acceptance explicitly selects `gpt-6-luna`; native tests do not establish model-answer quality.

Run `scripts/verification-scope.sh --base <commit> --head <commit>` to select gates, or `--full` for uncertain scope. Check Linux/backing-host headroom, use one serial bounded lane and disposable roots. Documentation-only changes need guidance/link checks, not Maven or containers.

Current capabilities and deferred work are in the [roadmap](docs/roadmap.md); observed verification is in [current verification status](docs/operations/testing.md#current-verification-status). Local success is not production deployment, image/CI or capacity acceptance. Docker/images, existing-service mutation, project integration and push require their own authorization.
